package dev.dove.agent.transform;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds the patches that apply to a class, by name or by constant-pool marker.
 *
 * <p>Every class the game loads passes through here, so marker matching walks the class's constant
 * pool once and only compares UTF-8 entries whose length matches a marker; nothing is parsed with
 * ASM unless a patch applies.
 */
public final class PatchRegistry {

    private final Map<String, List<ClassPatch>> byTarget = new HashMap<>();
    private final List<ClassPatch> markedPatches = new ArrayList<>();
    /** Marker bytes grouped by length; index i of markerOwners holds the patch for marker i. */
    private final Map<Integer, List<Integer>> markersByLength = new HashMap<>();
    private final List<byte[]> markers = new ArrayList<>();
    private final List<ClassPatch> markerOwners = new ArrayList<>();
    private int size;

    public void register(ClassPatch patch) {
        for (String target : patch.targets()) {
            byTarget.computeIfAbsent(target, k -> new ArrayList<>()).add(patch);
        }
        if (!patch.markers().isEmpty()) {
            markedPatches.add(patch);
            for (String marker : patch.markers()) {
                // Markers are ASCII, so UTF-8 equals the class file's modified UTF-8.
                byte[] bytes = marker.getBytes(StandardCharsets.UTF_8);
                markersByLength.computeIfAbsent(bytes.length, k -> new ArrayList<>()).add(markers.size());
                markers.add(bytes);
                markerOwners.add(patch);
            }
        }
        size++;
    }

    /** Patches for this class: name targets first, then marker matches in registration order. Never null. */
    public List<ClassPatch> patchesFor(String internalName, byte[] classBytes) {
        List<ClassPatch> named = byTarget.getOrDefault(internalName, List.of());
        if (markedPatches.isEmpty()) {
            return named;
        }
        boolean[] hit = matchMarkers(classBytes);
        if (hit == null) {
            return named;
        }
        List<ClassPatch> result = new ArrayList<>(named);
        for (ClassPatch patch : markedPatches) {
            if (!result.contains(patch) && matchedAny(patch, hit)) {
                result.add(patch);
            }
        }
        return result;
    }

    public int size() {
        return size;
    }

    private boolean matchedAny(ClassPatch patch, boolean[] hit) {
        for (int i = 0; i < hit.length; i++) {
            if (hit[i] && markerOwners.get(i) == patch) {
                return true;
            }
        }
        return false;
    }

    /** Which markers appear as UTF-8 constants, or null if none (or the bytes aren't a class file). */
    private boolean[] matchMarkers(byte[] b) {
        if (b.length < 10 || u4(b, 0) != 0xCAFEBABE) {
            return null;
        }
        boolean[] hit = null;
        int count = u2(b, 8);
        int offset = 10;
        for (int i = 1; i < count; i++) {
            int tag = b[offset];
            switch (tag) {
                case 1 -> { // Utf8
                    int length = u2(b, offset + 1);
                    List<Integer> candidates = markersByLength.get(length);
                    if (candidates != null) {
                        for (int marker : candidates) {
                            if (Arrays.equals(b, offset + 3, offset + 3 + length, markers.get(marker), 0, length)) {
                                if (hit == null) {
                                    hit = new boolean[markers.size()];
                                }
                                hit[marker] = true;
                            }
                        }
                    }
                    offset += 3 + length;
                }
                case 7, 8, 16, 19, 20 -> offset += 3; // Class, String, MethodType, Module, Package
                case 15 -> offset += 4; // MethodHandle
                case 3, 4, 9, 10, 11, 12, 17, 18 -> offset += 5;
                case 5, 6 -> { // Long, Double take two slots
                    offset += 9;
                    i++;
                }
                default -> {
                    return null; // unknown tag: not a class file we understand
                }
            }
        }
        return hit;
    }

    private static int u2(byte[] b, int offset) {
        return ((b[offset] & 0xFF) << 8) | (b[offset + 1] & 0xFF);
    }

    private static int u4(byte[] b, int offset) {
        return (u2(b, offset) << 16) | u2(b, offset + 2);
    }
}
