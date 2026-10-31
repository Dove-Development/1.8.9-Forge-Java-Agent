package dev.dove.agent.hooks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LinuxCpuTest {

    @Test
    void readsCpuLists() {
        assertArrayEquals(new int[]{0, 1, 2, 3, 8, 10, 11}, LinuxCpu.cpuList("0-3,8,10-11\n"));
        assertArrayEquals(new int[]{5}, LinuxCpu.cpuList("5"));
        assertEquals(32L * 1024 * 1024, LinuxCpu.size("32768K"));
        assertEquals(96L * 1024 * 1024, LinuxCpu.size("96M"));
        assertEquals(0, LinuxCpu.size(""));
    }

    @Test
    void findsThePerformanceCoresOfAHybridIntelCpu(@TempDir Path sys) throws IOException {
        // 4 performance cores (with their second threads, 0-7) and 4 efficiency cores (8-11), one L3.
        cpus(sys, "0-11");
        write(sys, "devices/cpu_core/cpus", "0-7");
        write(sys, "devices/cpu_atom/cpus", "8-11");
        for (int cpu = 0; cpu <= 11; cpu++) {
            l3(sys, cpu, "0-11", "24576K");
        }
        HybridCpu.Plan plan = HybridCpu.plan(LinuxCpu.cpus(sys), LinuxCpu.l3Caches(sys));

        assertNotNull(plan);
        assertArrayEquals(new int[]{0, 1, 2, 3, 4, 5, 6, 7}, plan.ids());
        assertTrue(plan.reason().startsWith("Hybrid CPU"));
    }

    @Test
    void findsTheVCacheCcdOfAnX3dCpu(@TempDir Path sys) throws IOException {
        // A 7950X3D: CCD 0 (cores 0-7, their second threads 16-23) with 96 MB of L3, CCD 1 with 32 MB.
        cpus(sys, "0-31");
        for (int cpu = 0; cpu <= 31; cpu++) {
            boolean vCache = cpu < 8 || (cpu >= 16 && cpu < 24);
            l3(sys, cpu, vCache ? "0-7,16-23" : "8-15,24-31", vCache ? "98304K" : "32768K");
        }
        List<HybridCpu.Cache> caches = LinuxCpu.l3Caches(sys);
        HybridCpu.Plan plan = HybridCpu.plan(LinuxCpu.cpus(sys), caches);

        assertEquals(2, caches.size());
        assertNotNull(plan);
        assertArrayEquals(new int[]{0, 1, 2, 3, 4, 5, 6, 7, 16, 17, 18, 19, 20, 21, 22, 23}, plan.ids());
        assertTrue(plan.reason().contains("96 MB"));
    }

    @Test
    void leavesACpuWithAlikeCoresAlone(@TempDir Path sys) throws IOException {
        // A 7950X: two CCDs with the same 32 MB, and no Intel hybrid lists.
        cpus(sys, "0-31");
        for (int cpu = 0; cpu <= 31; cpu++) {
            boolean first = cpu < 8 || (cpu >= 16 && cpu < 24);
            l3(sys, cpu, first ? "0-7,16-23" : "8-15,24-31", "32768K");
        }
        assertNull(HybridCpu.plan(LinuxCpu.cpus(sys), LinuxCpu.l3Caches(sys)));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void keepsAThreadOnTheChosenCores() throws Exception {
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                long[] before = LinuxCpu.Native.affinity(0);
                int first = Long.numberOfTrailingZeros(before[0]);
                assumeTrue(first < 64, "a CPU under 64 to use");
                long[] one = LinuxCpu.mask(new int[]{first});
                assertTrue(LinuxCpu.Native.setAffinity(0, one));
                assertArrayEquals(one, LinuxCpu.Native.affinity(0));
                assertTrue(LinuxCpu.Native.setAffinity(0, before));
            } catch (Throwable t) {
                failed.set(t);
            }
        });
        thread.start();
        thread.join();
        if (failed.get() != null) {
            throw new AssertionError(failed.get());
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void givesTheRenderThreadsChildrenTheirAffinityBack() throws Exception {
        AtomicReference<Throwable> failed = new AtomicReference<>();
        AtomicInteger restored = new AtomicInteger(-1);
        Thread render = new Thread(() -> {
            try {
                long[] original = LinuxCpu.Native.affinity(0);
                assumeTrue(Long.bitCount(original[0]) > 1, "more than one CPU");
                long[] fast = LinuxCpu.mask(new int[]{Long.numberOfTrailingZeros(original[0])});
                assertTrue(LinuxCpu.Native.setAffinity(0, fast));
                int self = LinuxCpu.Native.threadId();
                // A thread the render thread starts has its affinity, as on Linux every thread does.
                AtomicInteger child = new AtomicInteger();
                Object release = new Object();
                Thread worker = new Thread(() -> {
                    try {
                        child.set(LinuxCpu.Native.threadId());
                        synchronized (release) {
                            release.wait(5000);
                        }
                    } catch (Throwable ignored) {
                    }
                });
                worker.start();
                while (child.get() == 0) {
                    Thread.sleep(10);
                }
                assertArrayEquals(fast, LinuxCpu.Native.affinity(child.get()));

                restored.set(LinuxCpu.restoreOthers(Path.of("/proc/self/task"), self, fast, original));

                assertArrayEquals(original, LinuxCpu.Native.affinity(child.get()), "the child has the game's affinity back");
                assertArrayEquals(fast, LinuxCpu.Native.affinity(0), "the render thread keeps the fast cores");
                synchronized (release) {
                    release.notifyAll();
                }
                worker.join();
                assertTrue(LinuxCpu.Native.setAffinity(0, original));
            } catch (Throwable t) {
                failed.set(t);
            }
        });
        render.start();
        render.join();
        if (failed.get() != null) {
            throw new AssertionError(failed.get());
        }
        assertTrue(restored.get() >= 1);
    }

    private static void cpus(Path sys, String online) throws IOException {
        write(sys, "devices/system/cpu/online", online + "\n");
    }

    private static void l3(Path sys, int cpu, String shared, String size) throws IOException {
        String index = "devices/system/cpu/cpu" + cpu + "/cache/index3/";
        write(sys, index + "level", "3\n");
        write(sys, index + "shared_cpu_list", shared + "\n");
        write(sys, index + "size", size + "\n");
        // An L2 next to it, which must be passed over.
        String l2 = "devices/system/cpu/cpu" + cpu + "/cache/index2/";
        write(sys, l2 + "level", "2\n");
        write(sys, l2 + "shared_cpu_list", cpu + "\n");
        write(sys, l2 + "size", "1024K\n");
    }

    private static void write(Path sys, String file, String text) throws IOException {
        Path path = sys.resolve(file);
        Files.createDirectories(path.getParent());
        Files.writeString(path, text);
    }
}
