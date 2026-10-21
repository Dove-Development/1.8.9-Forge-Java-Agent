package dev.dove.agent.transform;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class DoveTransformerTest {

    private static final String TARGET = "test/Target";
    private static final ClassLoader LOADER = DoveTransformerTest.class.getClassLoader();

    @Test
    void appliesPatchToTargetClass() {
        PatchRegistry registry = new PatchRegistry();
        registry.register(new MarkerPatch());
        DoveTransformer transformer = new DoveTransformer(registry);

        byte[] patched = transformer.transform(LOADER, TARGET, null, null, emptyClass(TARGET));

        assertNotNull(patched);
        ClassNode node = new ClassNode();
        new ClassReader(patched).accept(node, 0);
        assertEquals(1, node.fields.size());
        assertEquals("dove$patched", node.fields.getFirst().name);
    }

    @Test
    void ignoresClassesWithoutPatches() {
        PatchRegistry registry = new PatchRegistry();
        registry.register(new MarkerPatch());
        DoveTransformer transformer = new DoveTransformer(registry);

        assertNull(transformer.transform(LOADER, "test/Other", null, null, emptyClass("test/Other")));
    }

    private static byte[] emptyClass(String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static final class MarkerPatch implements ClassPatch {
        @Override
        public String name() {
            return "Marker";
        }

        @Override
        public Set<String> targets() {
            return Set.of(TARGET);
        }

        @Override
        public boolean apply(ClassNode node) {
            node.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "dove$patched", "Z", null, null);
            return true;
        }
    }
}
