package dev.dove.agent.util;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassFilesTest {

    @Test
    void findsExactUtf8Entries() {
        byte[] annotated = classWith("a/b/C", writer -> writer.visitAnnotation("Lnet/minecraftforge/fml/relauncher/SideOnly;", true).visitEnd());

        assertTrue(ClassFiles.hasUtf8(annotated, "Lnet/minecraftforge/fml/relauncher/SideOnly;"));
        assertFalse(ClassFiles.hasUtf8(annotated, "Lnet/minecraftforge/fml/relauncher/Side;"));
        assertFalse(ClassFiles.hasUtf8(annotated, "exit", "halt"));
    }

    @Test
    void unreadableBytesCountAsPossiblyMatching() {
        assertTrue(ClassFiles.hasUtf8(new byte[]{1, 2, 3}, "x"));
    }

    private static byte[] classWith(String name, java.util.function.Consumer<ClassWriter> body) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        body.accept(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }
}
