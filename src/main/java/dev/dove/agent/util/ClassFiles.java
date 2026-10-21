package dev.dove.agent.util;

import java.nio.charset.StandardCharsets;

/**
 * Questions about a class file answered straight from its constant pool, without ASM: cheap enough
 * to ask of every class the game loads.
 */
public final class ClassFiles {

    private ClassFiles() {
    }

    /** Whether the constant pool has a UTF-8 entry exactly equal to any of {@code values} (ASCII only). */
    public static boolean hasUtf8(byte[] classFile, String... values) {
        byte[][] wanted = new byte[values.length][];
        for (int i = 0; i < values.length; i++) {
            wanted[i] = values[i].getBytes(StandardCharsets.US_ASCII);
        }
        int[] offsets = utf8Offsets(classFile);
        if (offsets == null) {
            return true; // unreadable: assume it might, so callers fall back to the full path
        }
        for (int offset : offsets) {
            if (offset == 0) {
                continue;
            }
            int length = u2(classFile, offset + 1);
            for (byte[] value : wanted) {
                if (value.length == length && regionEquals(classFile, offset + 3, value)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Offsets of the UTF-8 entries' tags, indexed by constant pool index (0 where the entry is not
     * UTF-8), or null if this is not a class file this can read.
     */
    private static int[] utf8Offsets(byte[] b) {
        if (b == null || b.length < 10 || u2(b, 0) != 0xCAFE || u2(b, 2) != 0xBABE) {
            return null;
        }
        int count = u2(b, 8);
        int[] offsets = new int[count];
        int offset = 10;
        for (int i = 1; i < count; i++) {
            if (offset >= b.length) {
                return null;
            }
            int tag = b[offset];
            if (tag == 1) {
                offsets[i] = offset;
            }
            int size = entrySize(b, offset);
            if (size < 0) {
                return null;
            }
            offset += size;
            if (tag == 5 || tag == 6) {
                i++;
            }
        }
        return offset <= b.length ? offsets : null;
    }

    /** Size of the constant pool entry at {@code offset}, or -1 for an unknown tag. */
    private static int entrySize(byte[] b, int offset) {
        return switch (b[offset]) {
            case 1 -> 3 + u2(b, offset + 1); // Utf8
            case 7, 8, 16, 19, 20 -> 3; // Class, String, MethodType, Module, Package
            case 15 -> 4; // MethodHandle
            case 3, 4, 9, 10, 11, 12, 17, 18 -> 5;
            case 5, 6 -> 9; // Long, Double
            default -> -1;
        };
    }

    private static boolean regionEquals(byte[] b, int start, byte[] value) {
        for (int i = 0; i < value.length; i++) {
            if (b[start + i] != value[i]) {
                return false;
            }
        }
        return true;
    }

    private static int u2(byte[] b, int offset) {
        return ((b[offset] & 0xFF) << 8) | (b[offset + 1] & 0xFF);
    }
}
