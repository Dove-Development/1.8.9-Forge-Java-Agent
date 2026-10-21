package dev.dove.agent.transform;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatchRegistryTest {

    @Test
    void matchesMarkerInConstantPool() {
        PatchRegistry registry = new PatchRegistry();
        ClassPatch patch = markerPatch("addURL");
        registry.register(patch);

        assertEquals(List.of(patch), registry.patchesFor("a/A", classWithString("a/A", "addURL")));
    }

    @Test
    void ignoresLongerStringsContainingMarker() {
        PatchRegistry registry = new PatchRegistry();
        registry.register(markerPatch("addURL"));

        assertTrue(registry.patchesFor("a/A", classWithString("a/A", "addURLs")).isEmpty());
    }

    @Test
    void namedTargetComesFirstAndIsNotDuplicated() {
        PatchRegistry registry = new PatchRegistry();
        ClassPatch both = new ClassPatch() {
            public String name() { return "both"; }
            public Set<String> targets() { return Set.of("a/A"); }
            public Set<String> markers() { return Set.of("addURL"); }
            public boolean apply(ClassNode node) { return false; }
        };
        ClassPatch marker = markerPatch("addURL");
        registry.register(marker);
        registry.register(both);

        assertEquals(List.of(both, marker), registry.patchesFor("a/A", classWithString("a/A", "addURL")));
    }

    private static ClassPatch markerPatch(String marker) {
        return new ClassPatch() {
            public String name() { return "marker"; }
            public Set<String> markers() { return Set.of(marker); }
            public boolean apply(ClassNode node) { return false; }
        };
    }

    private static byte[] classWithString(String name, String constant) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.newConst(constant);
        writer.visitEnd();
        return writer.toByteArray();
    }
}
