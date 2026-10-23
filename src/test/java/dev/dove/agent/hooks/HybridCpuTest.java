package dev.dove.agent.hooks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HybridCpuTest {

    @Test
    void findsThePerformanceCoresOfAHybridCpu() {
        // As Windows reports a 2 P-core + 2 E-core CPU: P-cores have the higher efficiency class.
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = cpuSetInformation(arena, new int[][]{{256, 1}, {257, 1}, {258, 0}, {259, 0}});
            List<HybridCpu.CpuSet> sets = HybridCpu.parse(buffer, buffer.byteSize());

            assertEquals(4, sets.size());
            assertTrue(HybridCpu.isHybrid(sets));
            assertArrayEquals(new int[]{256, 257}, HybridCpu.performanceCores(sets));
        }
    }

    @Test
    void leavesACpuWithAlikeCoresAlone() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = cpuSetInformation(arena, new int[][]{{256, 0}, {257, 0}});
            List<HybridCpu.CpuSet> sets = HybridCpu.parse(buffer, buffer.byteSize());

            assertFalse(HybridCpu.isHybrid(sets));
            assertArrayEquals(new int[]{256, 257}, HybridCpu.performanceCores(sets));
        }
    }

    @Test
    void picksTheVCacheCcdOfAnX3dCpu() {
        // As a 7950X3D: 32 logical processors, the first 16 under a 96 MB L3, the rest under 32 MB.
        List<HybridCpu.CpuSet> sets = logicalProcessors(32);
        List<HybridCpu.Cache> caches = List.of(
                new HybridCpu.Cache(96L << 20, 0, 0x0000FFFFL),
                new HybridCpu.Cache(32L << 20, 0, 0xFFFF0000L));

        HybridCpu.Plan plan = HybridCpu.plan(sets, caches);

        assertArrayEquals(java.util.stream.IntStream.range(0, 16).map(i -> 256 + i).toArray(), plan.ids());
        assertTrue(plan.reason().contains("3D V-Cache"));
    }

    @Test
    void leavesCcdsThatAreAllAlikeAlone() {
        // As a 7950X: two CCDs with the same 32 MB L3; and a CPU with one L3.
        List<HybridCpu.CpuSet> sets = logicalProcessors(32);

        assertNull(HybridCpu.plan(sets, List.of(
                new HybridCpu.Cache(32L << 20, 0, 0x0000FFFFL),
                new HybridCpu.Cache(32L << 20, 0, 0xFFFF0000L))));
        assertNull(HybridCpu.plan(sets, List.of(new HybridCpu.Cache(32L << 20, 0, 0xFFFFFFFFL))));
    }

    @Test
    void readsWindowsCacheRecords() {
        // SYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX for RelationCache: an L2 (left out) and two L3s.
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(3 * 80L, 8);
            cacheRecord(buffer, 0, 2, 1L << 20, 0x3L);
            cacheRecord(buffer, 80, 3, 96L << 20, 0x0000FFFFL);
            cacheRecord(buffer, 160, 3, 32L << 20, 0xFFFF0000L);

            List<HybridCpu.Cache> caches = HybridCpu.parseCaches(buffer, buffer.byteSize());

            assertEquals(List.of(new HybridCpu.Cache(96L << 20, 0, 0x0000FFFFL), new HybridCpu.Cache(32L << 20, 0, 0xFFFF0000L)), caches);
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void readsThisMachinesCaches() throws Throwable {
        List<HybridCpu.Cache> caches = HybridCpu.l3Caches();

        assertFalse(caches.isEmpty());
        assertTrue(caches.stream().allMatch(cache -> cache.size() > 0 && cache.mask() != 0));
    }

    @Test
    void readsTheOptionValue() {
        assertEquals(HybridCpu.Mode.AUTO, HybridCpu.Mode.parse(null));
        assertEquals(HybridCpu.Mode.AUTO, HybridCpu.Mode.parse("auto"));
        assertEquals(HybridCpu.Mode.OFF, HybridCpu.Mode.parse("false"));
        assertEquals(HybridCpu.Mode.OFF, HybridCpu.Mode.parse("off"));
        assertEquals(HybridCpu.Mode.FORCE, HybridCpu.Mode.parse("FORCE"));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void readsThisMachinesCores() throws Throwable {
        List<HybridCpu.CpuSet> sets = HybridCpu.cpuSets();

        assertEquals(Runtime.getRuntime().availableProcessors(), sets.size());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsAcceptsEachSetting() throws Throwable {
        assertTrue(HybridCpu.raiseProcessPriority());
        assertTrue(HybridCpu.leaveEfficiencyMode());
        int[] all = HybridCpu.cpuSets().stream().mapToInt(HybridCpu.CpuSet::id).toArray();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                assertTrue(HybridCpu.preferCores(all));
                assertTrue(HybridCpu.preferCores(new int[0])); // and lifted again
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        thread.start();
        thread.join();
        if (failure.get() != null) {
            throw failure.get();
        }
    }

    private static List<HybridCpu.CpuSet> logicalProcessors(int count) {
        return java.util.stream.IntStream.range(0, count).mapToObj(i -> new HybridCpu.CpuSet(256 + i, 0, 0, i)).toList();
    }

    /** One SYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX cache record of 80 bytes. */
    private static void cacheRecord(MemorySegment buffer, long offset, int level, long size, long mask) {
        buffer.set(ValueLayout.JAVA_INT, offset, HybridCpu.RELATION_CACHE);
        buffer.set(ValueLayout.JAVA_INT, offset + 4, 80);
        buffer.set(ValueLayout.JAVA_BYTE, offset + 8, (byte) level);
        buffer.set(ValueLayout.JAVA_INT, offset + 12, (int) size);
        buffer.set(ValueLayout.JAVA_LONG, offset + 40, mask);
        buffer.set(ValueLayout.JAVA_SHORT, offset + 48, (short) 0);
    }

    /** SYSTEM_CPU_SET_INFORMATION entries of 32 bytes: Size, Type, Id at 8, EfficiencyClass at 18. */
    private static MemorySegment cpuSetInformation(Arena arena, int[][] sets) {
        MemorySegment buffer = arena.allocate(32L * sets.length, 8);
        for (int i = 0; i < sets.length; i++) {
            long offset = 32L * i;
            buffer.set(ValueLayout.JAVA_INT, offset, 32);
            buffer.set(ValueLayout.JAVA_INT, offset + 4, HybridCpu.CPU_SET_INFORMATION);
            buffer.set(ValueLayout.JAVA_INT, offset + 8, sets[i][0]);
            buffer.set(ValueLayout.JAVA_BYTE, offset + 18, (byte) sets[i][1]);
        }
        return buffer;
    }
}
