package dev.dove.agent.hooks;

import dev.dove.agent.util.AgentLog;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Settings for CPUs whose cores are not all alike, on Windows only:
 *
 * <ul>
 *   <li>Intel CPUs with performance and efficiency cores (12th generation and later): the fast
 *   cores are the performance cores;</li>
 *   <li>AMD Ryzen X3D CPUs with two CCDs of which only one has the 3D V-Cache (7900X3D, 7950X3D,
 *   9900X3D, 9950X3D): the fast cores are those sharing the larger L3 cache. AMD's driver moves
 *   only games it recognises there, and it often doesn't recognise Minecraft.</li>
 * </ul>
 *
 * <p>On such a CPU the game process runs at Above Normal priority, Windows is asked not to run it in
 * efficiency mode (EcoQoS), and the render thread (the thread running {@code premain}, which
 * becomes Minecraft's render thread on every version) is kept on the fast cores: any of them,
 * never pinned to one.
 *
 * <p>On other CPUs nothing changes: there the same settings measured no gain. Each setting that
 * Windows refuses or that fails is logged and left out; the game starts either way.
 */
public final class HybridCpu {

    /** {@code auto} (default): only on the CPUs above; {@code off}; {@code force}: also on other CPUs, for testing. */
    public enum Mode {
        AUTO, OFF, FORCE;

        public static Mode parse(String value) {
            if (value == null || value.isBlank()) {
                return AUTO;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "off", "false" -> OFF;
                case "force" -> FORCE;
                default -> AUTO;
            };
        }
    }

    /**
     * One logical processor as Windows describes it: its CPU set id, how fast its core is (higher is
     * faster), and its processor group and number in the group.
     */
    record CpuSet(int id, int efficiencyClass, int group, int logicalIndex) {
        CpuSet(int id, int efficiencyClass) {
            this(id, efficiencyClass, 0, 0);
        }
    }

    /** An L3 cache: its size and the logical processors of one group that share it. */
    record Cache(long size, int group, long mask) {
        boolean covers(CpuSet set) {
            return set.group() == group && set.logicalIndex() < 64 && (mask & (1L << set.logicalIndex())) != 0;
        }
    }

    /** The fast cores and why they were picked, for the log. */
    record Plan(int[] ids, String reason) {
    }

    // winnt.h / processthreadsapi.h
    static final int ABOVE_NORMAL_PRIORITY_CLASS = 0x8000;
    static final int PROCESS_POWER_THROTTLING = 4; // PROCESS_INFORMATION_CLASS.ProcessPowerThrottling
    static final int PROCESS_POWER_THROTTLING_EXECUTION_SPEED = 0x1;
    static final int CPU_SET_INFORMATION = 0; // CPU_SET_INFORMATION_TYPE.CpuSetInformation
    static final int RELATION_CACHE = 2; // LOGICAL_PROCESSOR_RELATIONSHIP.RelationCache
    /** One L3 at least this much larger than another means a 3D V-Cache CCD (96 MB against 32 MB). */
    static final double V_CACHE_RATIO = 1.5;

    private HybridCpu() {
    }

    /** Called from premain, on the thread that becomes the render thread. */
    public static void apply(Mode mode) {
        if (mode == Mode.OFF || !System.getProperty("os.name", "").startsWith("Windows")) {
            return;
        }
        List<CpuSet> sets;
        List<Cache> caches;
        try {
            sets = cpuSets();
            caches = l3Caches();
        } catch (Throwable t) {
            AgentLog.warn("Cannot read the CPU's cores; leaving scheduling to Windows: " + t);
            return;
        }
        Plan plan = plan(sets, caches);
        if (plan == null && mode != Mode.FORCE) {
            AgentLog.info("CPU cores are all alike (no efficiency cores, no 3D V-Cache CCD); scheduling left to Windows");
            return;
        }
        if (plan == null) {
            plan = new Plan(sets.stream().mapToInt(CpuSet::id).toArray(), "hybridCpu=force, all cores");
        }
        int[] fast = plan.ids();
        AgentLog.info(plan.reason() + ": " + fast.length + " of " + sets.size() + " logical processors are the fast cores");
        set("process priority Above Normal", HybridCpu::raiseProcessPriority);
        set("no efficiency mode (EcoQoS) for the game", HybridCpu::leaveEfficiencyMode);
        set("render thread on the fast cores", () -> preferCores(fast));
    }

    /** Applies one setting; a refusal or failure is logged and only costs that setting. */
    private static void set(String what, Setting setting) {
        try {
            if (setting.apply()) {
                AgentLog.info("Windows: " + what);
            } else {
                AgentLog.warn("Windows refused " + what);
            }
        } catch (Throwable t) {
            AgentLog.warn("Cannot set " + what + ": " + t);
        }
    }

    @FunctionalInterface
    interface Setting {
        boolean apply() throws Throwable;
    }

    /** Which cores are the fast ones, or null when the CPU's cores are all alike. */
    static Plan plan(List<CpuSet> sets, List<Cache> l3Caches) {
        if (isHybrid(sets)) {
            return new Plan(performanceCores(sets), "Hybrid CPU (performance and efficiency cores)");
        }
        int[] vCache = vCacheCores(sets, l3Caches);
        if (vCache != null) {
            long size = l3Caches.stream().mapToLong(Cache::size).max().orElse(0);
            return new Plan(vCache, "CPU with 3D V-Cache on one CCD (" + size / (1024 * 1024) + " MB L3)");
        }
        return null;
    }

    /**
     * The cores sharing the largest L3 cache, when the CPU has several L3 caches and the largest is
     * clearly bigger than the smallest (an X3D CPU with one V-Cache CCD); otherwise null, as on CPUs
     * with one L3 or with CCDs that are all alike.
     */
    static int[] vCacheCores(List<CpuSet> sets, List<Cache> l3Caches) {
        if (l3Caches.size() < 2) {
            return null;
        }
        Cache largest = l3Caches.stream().max(Comparator.comparingLong(Cache::size)).orElseThrow();
        long smallest = l3Caches.stream().mapToLong(Cache::size).min().orElseThrow();
        if (largest.size() < smallest * V_CACHE_RATIO) {
            return null;
        }
        int[] ids = sets.stream().filter(largest::covers).mapToInt(CpuSet::id).toArray();
        return ids.length > 0 && ids.length < sets.size() ? ids : null;
    }

    static boolean isHybrid(List<CpuSet> sets) {
        return sets.stream().mapToInt(CpuSet::efficiencyClass).distinct().count() > 1;
    }

    /** The CPU set ids of the fastest cores (all of them on a CPU whose cores are all alike). */
    static int[] performanceCores(List<CpuSet> sets) {
        int best = sets.stream().mapToInt(CpuSet::efficiencyClass).max().orElse(0);
        return sets.stream().filter(set -> set.efficiencyClass() == best).mapToInt(CpuSet::id).toArray();
    }

    /** The CPU sets of this machine, from GetSystemCpuSetInformation. */
    static List<CpuSet> cpuSets() throws Throwable {
        MethodHandle get = function("kernel32", "GetSystemCpuSetInformation", FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment returned = arena.allocate(ValueLayout.JAVA_INT);
            // First call: how big the buffer must be (fails with ERROR_INSUFFICIENT_BUFFER).
            int ignored = (int) get.invokeExact(MemorySegment.NULL, 0, returned, MemorySegment.NULL, 0);
            int length = returned.get(ValueLayout.JAVA_INT, 0);
            MemorySegment buffer = arena.allocate(length, 8);
            if ((int) get.invokeExact(buffer, length, returned, MemorySegment.NULL, 0) == 0) {
                throw new IllegalStateException("GetSystemCpuSetInformation failed");
            }
            return parse(buffer, returned.get(ValueLayout.JAVA_INT, 0));
        }
    }

    /**
     * Reads SYSTEM_CPU_SET_INFORMATION entries: DWORD Size, DWORD Type, then for CpuSetInformation
     * DWORD Id at 8, WORD Group at 12, BYTE LogicalProcessorIndex at 14 and BYTE EfficiencyClass at 18.
     * Each entry says its own size.
     */
    static List<CpuSet> parse(MemorySegment buffer, long length) {
        List<CpuSet> sets = new ArrayList<>();
        long offset = 0;
        while (offset + 20 <= length) {
            int size = buffer.get(ValueLayout.JAVA_INT_UNALIGNED, offset);
            if (size <= 0) {
                break;
            }
            if (buffer.get(ValueLayout.JAVA_INT_UNALIGNED, offset + 4) == CPU_SET_INFORMATION) {
                int id = buffer.get(ValueLayout.JAVA_INT_UNALIGNED, offset + 8);
                int group = Short.toUnsignedInt(buffer.get(ValueLayout.JAVA_SHORT_UNALIGNED, offset + 12));
                int logicalIndex = Byte.toUnsignedInt(buffer.get(ValueLayout.JAVA_BYTE, offset + 14));
                int efficiencyClass = Byte.toUnsignedInt(buffer.get(ValueLayout.JAVA_BYTE, offset + 18));
                sets.add(new CpuSet(id, efficiencyClass, group, logicalIndex));
            }
            offset += size;
        }
        return sets;
    }

    /** The L3 caches of this machine, from GetLogicalProcessorInformationEx(RelationCache). */
    static List<Cache> l3Caches() throws Throwable {
        MethodHandle get = function("kernel32", "GetLogicalProcessorInformationEx", FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment length = arena.allocate(ValueLayout.JAVA_INT);
            // First call: how big the buffer must be (fails with ERROR_INSUFFICIENT_BUFFER).
            int ignored = (int) get.invokeExact(RELATION_CACHE, MemorySegment.NULL, length);
            MemorySegment buffer = arena.allocate(length.get(ValueLayout.JAVA_INT, 0), 8);
            if ((int) get.invokeExact(RELATION_CACHE, buffer, length) == 0) {
                throw new IllegalStateException("GetLogicalProcessorInformationEx failed");
            }
            return parseCaches(buffer, length.get(ValueLayout.JAVA_INT, 0));
        }
    }

    /**
     * Reads SYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX entries for RelationCache: DWORD Relationship,
     * DWORD Size, then CACHE_RELATIONSHIP at 8: BYTE Level at 8, DWORD CacheSize at 12, and the
     * first GROUP_AFFINITY at 40 (KAFFINITY Mask, WORD Group at 48). Only level 3 caches are kept.
     */
    static List<Cache> parseCaches(MemorySegment buffer, long length) {
        List<Cache> caches = new ArrayList<>();
        long offset = 0;
        while (offset + 50 <= length) {
            int size = buffer.get(ValueLayout.JAVA_INT_UNALIGNED, offset + 4);
            if (size <= 0) {
                break;
            }
            if (buffer.get(ValueLayout.JAVA_INT_UNALIGNED, offset) == RELATION_CACHE
                    && Byte.toUnsignedInt(buffer.get(ValueLayout.JAVA_BYTE, offset + 8)) == 3) {
                long cacheSize = Integer.toUnsignedLong(buffer.get(ValueLayout.JAVA_INT_UNALIGNED, offset + 12));
                long mask = buffer.get(ValueLayout.JAVA_LONG_UNALIGNED, offset + 40);
                int group = Short.toUnsignedInt(buffer.get(ValueLayout.JAVA_SHORT_UNALIGNED, offset + 48));
                caches.add(new Cache(cacheSize, group, mask));
            }
            offset += size;
        }
        return caches;
    }

    static boolean raiseProcessPriority() throws Throwable {
        MethodHandle set = function("kernel32", "SetPriorityClass",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        return (int) set.invokeExact(currentProcess(), ABOVE_NORMAL_PRIORITY_CLASS) != 0;
    }

    /** PROCESS_POWER_THROTTLING_STATE {Version 1, ControlMask EXECUTION_SPEED, StateMask 0}: never throttled. */
    static boolean leaveEfficiencyMode() throws Throwable {
        MethodHandle set = function("kernel32", "SetProcessInformation", FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(12, 4);
            state.set(ValueLayout.JAVA_INT, 0, 1);
            state.set(ValueLayout.JAVA_INT, 4, PROCESS_POWER_THROTTLING_EXECUTION_SPEED);
            state.set(ValueLayout.JAVA_INT, 8, 0);
            return (int) set.invokeExact(currentProcess(), PROCESS_POWER_THROTTLING, state, 12) != 0;
        }
    }

    /** Lets Windows run the calling thread on these CPU sets only; an empty array lifts that again. */
    static boolean preferCores(int[] ids) throws Throwable {
        MethodHandle set = function("kernel32", "SetThreadSelectedCpuSets", FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        MethodHandle current = function("kernel32", "GetCurrentThread", FunctionDescriptor.of(ValueLayout.ADDRESS));
        MemorySegment thread = (MemorySegment) current.invokeExact();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment list = ids.length == 0 ? MemorySegment.NULL : arena.allocateFrom(ValueLayout.JAVA_INT, ids);
            return (int) set.invokeExact(thread, list, ids.length) != 0;
        }
    }

    private static MemorySegment currentProcess() throws Throwable {
        MethodHandle current = function("kernel32", "GetCurrentProcess", FunctionDescriptor.of(ValueLayout.ADDRESS));
        return (MemorySegment) current.invokeExact();
    }

    private static MethodHandle function(String library, String name, FunctionDescriptor descriptor) {
        MemorySegment address = SymbolLookup.libraryLookup(library, Arena.global()).find(name)
                .orElseThrow(() -> new UnsatisfiedLinkError(library + "!" + name));
        return Linker.nativeLinker().downcallHandle(address, descriptor);
    }
}
