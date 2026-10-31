package dev.dove.agent.hooks;

import dev.dove.agent.util.AgentLog;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@link HybridCpu} on Linux: the CPU's layout from sysfs, and the render thread kept on the fast
 * cores with {@code sched_setaffinity}.
 *
 * <p>Intel's hybrid CPUs list their performance cores in {@code /sys/devices/cpu_core/cpus} (and
 * their efficiency cores in {@code cpu_atom}); each CPU's L3 cache, its size and the CPUs sharing
 * it, is in {@code /sys/devices/system/cpu/cpuN/cache/indexM}. Both are read into HybridCpu's
 * CpuSet and Cache, so the same plan picks the fast cores; a CpuSet's id is the CPU's number.
 *
 * <p>Unlike Windows' CPU sets, a Linux thread's affinity passes to the threads it starts, and the
 * render thread starts most of the game's. A watcher therefore gives every other thread that has
 * the render thread's affinity back the one the game started with, every couple of seconds: only
 * the render thread keeps to the fast cores, as on Windows. Linux lets a normal user lower a
 * thread's priority only and has no EcoQoS, so HybridCpu's two other Windows settings have no
 * Linux part.
 */
final class LinuxCpu {

    static final Path SYS = Path.of("/sys");
    /** glibc's cpu_set_t: room for 1024 CPUs. */
    static final int MASK_BYTES = 128;
    static final long WATCH_MILLIS = 2000;

    private LinuxCpu() {
    }

    /** The online CPUs; on a hybrid Intel CPU its performance cores have efficiency class 1, the others 0. */
    static List<HybridCpu.CpuSet> cpus(Path sys) throws IOException {
        int[] online = cpuList(Files.readString(sys.resolve("devices/system/cpu/online")));
        Path cores = sys.resolve("devices/cpu_core/cpus");
        Set<Integer> performance = new HashSet<>();
        if (Files.exists(cores) && Files.exists(sys.resolve("devices/cpu_atom/cpus"))) {
            for (int cpu : cpuList(Files.readString(cores))) {
                performance.add(cpu);
            }
        }
        List<HybridCpu.CpuSet> sets = new ArrayList<>();
        for (int cpu : online) {
            sets.add(new HybridCpu.CpuSet(cpu, performance.contains(cpu) ? 1 : 0, cpu / 64, cpu % 64));
        }
        return sets;
    }

    /** The L3 caches of the online CPUs: one per group of CPUs sharing one. */
    static List<HybridCpu.Cache> l3Caches(Path sys) throws IOException {
        Map<String, HybridCpu.Cache> caches = new LinkedHashMap<>();
        for (int cpu : cpuList(Files.readString(sys.resolve("devices/system/cpu/online")))) {
            Path cache = sys.resolve("devices/system/cpu/cpu" + cpu + "/cache");
            if (!Files.isDirectory(cache)) {
                continue;
            }
            try (DirectoryStream<Path> indexes = Files.newDirectoryStream(cache, "index*")) {
                for (Path index : indexes) {
                    if (!"3".equals(read(index.resolve("level")))) {
                        continue;
                    }
                    String shared = read(index.resolve("shared_cpu_list"));
                    if (shared == null || caches.containsKey(shared)) {
                        continue;
                    }
                    int[] cpus = cpuList(shared);
                    int group = cpus.length > 0 ? cpus[0] / 64 : 0;
                    long mask = 0;
                    for (int member : cpus) {
                        if (member / 64 == group) {
                            mask |= 1L << (member % 64);
                        }
                    }
                    caches.put(shared, new HybridCpu.Cache(size(read(index.resolve("size"))), group, mask));
                }
            }
        }
        return new ArrayList<>(caches.values());
    }

    /** A sysfs CPU list ("0-3,8,10-11") as CPU numbers. */
    static int[] cpuList(String list) {
        List<Integer> cpus = new ArrayList<>();
        for (String part : list.trim().split(",")) {
            if (part.isBlank()) {
                continue;
            }
            String[] range = part.trim().split("-");
            int first = Integer.parseInt(range[0].trim());
            int last = range.length > 1 ? Integer.parseInt(range[1].trim()) : first;
            for (int cpu = first; cpu <= last; cpu++) {
                cpus.add(cpu);
            }
        }
        return cpus.stream().mapToInt(Integer::intValue).toArray();
    }

    /** A sysfs cache size ("32768K", "96M") in bytes; 0 when there is none. */
    static long size(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        String value = text.trim().toUpperCase(Locale.ROOT);
        long unit = switch (value.charAt(value.length() - 1)) {
            case 'K' -> 1024L;
            case 'M' -> 1024L * 1024;
            case 'G' -> 1024L * 1024 * 1024;
            default -> 1;
        };
        String digits = unit == 1 ? value : value.substring(0, value.length() - 1);
        return Long.parseLong(digits.trim()) * unit;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file).trim();
        } catch (IOException e) {
            return null;
        }
    }

    /** An affinity mask with these CPUs (those under 1024). */
    static long[] mask(int[] cpus) {
        long[] words = new long[MASK_BYTES / 8];
        for (int cpu : cpus) {
            if (cpu >= 0 && cpu < MASK_BYTES * 8) {
                words[cpu / 64] |= 1L << (cpu % 64);
            }
        }
        return words;
    }

    /**
     * Keeps the calling thread (the render thread) on these CPUs, and starts the watcher that gives
     * the threads it starts the game's own affinity back.
     */
    static boolean preferCores(int[] cpus) throws Throwable {
        long[] original = Native.affinity(0);
        long[] fast = mask(cpus);
        if (!Native.setAffinity(0, fast)) {
            return false;
        }
        int render = Native.threadId();
        Thread watcher = new Thread(() -> watch(render, fast, original), "Dove agent CPU affinity");
        watcher.setDaemon(true);
        watcher.start();
        return true;
    }

    /** Every couple of seconds: each thread but the render thread that has its affinity gets the original one. */
    private static void watch(int render, long[] fast, long[] original) {
        while (true) {
            restoreOthers(Path.of("/proc/self/task"), render, fast, original);
            try {
                Thread.sleep(WATCH_MILLIS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** One look at the game's threads; returns how many got the original affinity back. */
    static int restoreOthers(Path tasks, int render, long[] fast, long[] original) {
        int restored = 0;
        try (DirectoryStream<Path> threads = Files.newDirectoryStream(tasks)) {
            for (Path task : threads) {
                int tid;
                try {
                    tid = Integer.parseInt(task.getFileName().toString());
                } catch (NumberFormatException e) {
                    continue;
                }
                if (tid == render) {
                    continue;
                }
                try {
                    if (Arrays.equals(Native.affinity(tid), fast) && Native.setAffinity(tid, original)) {
                        restored++;
                    }
                } catch (Throwable ignored) {
                    // The thread has ended since the folder was read.
                }
            }
        } catch (IOException e) {
            AgentLog.warn("Cannot look at the game's threads: " + e);
        }
        return restored;
    }

    /** glibc's sched_getaffinity, sched_setaffinity and gettid; looked up on first use, on Linux only. */
    static final class Native {
        private static final Linker LINKER = Linker.nativeLinker();
        private static final MethodHandle GET = function("sched_getaffinity",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
        private static final MethodHandle SET = function("sched_setaffinity",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
        private static final MethodHandle GETTID = function("gettid", FunctionDescriptor.of(ValueLayout.JAVA_INT));

        private Native() {
        }

        /** The affinity of thread {@code tid} (0: the calling thread). */
        static long[] affinity(int tid) throws Throwable {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment mask = arena.allocate(MASK_BYTES, 8);
                if ((int) GET.invokeExact(tid, (long) MASK_BYTES, mask) != 0) {
                    throw new IllegalStateException("sched_getaffinity failed for thread " + tid);
                }
                return mask.toArray(ValueLayout.JAVA_LONG);
            }
        }

        static boolean setAffinity(int tid, long[] words) throws Throwable {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment mask = arena.allocateFrom(ValueLayout.JAVA_LONG, words);
                return (int) SET.invokeExact(tid, (long) MASK_BYTES, mask) == 0;
            }
        }

        static int threadId() throws Throwable {
            return (int) GETTID.invokeExact();
        }

        private static MethodHandle function(String name, FunctionDescriptor descriptor) {
            MemorySegment address = LINKER.defaultLookup().find(name)
                    .orElseThrow(() -> new UnsatisfiedLinkError("libc!" + name));
            return LINKER.downcallHandle(address, descriptor);
        }
    }
}
