package dev.dove.agent.patches.fml;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.ClassReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FmlSignatureNoticePatchTest {

    @Test
    void lowersForge189sMessageToInfo() {
        ClassNode node = checker("net/minecraftforge/fml/relauncher/FMLRelaunchLog", "severe",
                "(Ljava/lang/String;[Ljava/lang/Object;)V", true);

        assertTrue(new FmlSignatureNoticePatch().apply(node));
        assertEquals(FmlSignatureNoticePatch.NOTICE, message(node));
        assertEquals("info", logCall(node).name);
    }

    @Test
    void lowersForge1122sMessageToInfo() {
        ClassNode node = checker("org/apache/logging/log4j/Logger", "error", "(Ljava/lang/String;)V", false);

        assertTrue(new FmlSignatureNoticePatch().apply(node));
        assertEquals(FmlSignatureNoticePatch.NOTICE, message(node));
        assertEquals("info", logCall(node).name);
    }

    @Test
    void leavesAnUnknownLoggingCallAlone() {
        ClassNode node = checker("some/Other", "warn", "(Ljava/lang/String;)V", false);

        assertFalse(new FmlSignatureNoticePatch().apply(node));
        assertEquals(FmlSignatureNoticePatch.ORIGINAL, message(node));
    }

    /** A class that logs the original message the way FMLSanityChecker does. */
    private static ClassNode checker(String owner, String name, String desc, boolean varargs) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_6, Opcodes.ACC_PUBLIC, FmlSignatureNoticePatch.TARGET, null, "java/lang/Object", null);
        MethodVisitor call = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "call", "()V", null, null);
        call.visitCode();
        boolean instance = owner.startsWith("org/apache");
        if (instance) {
            call.visitInsn(Opcodes.ACONST_NULL);
        }
        call.visitLdcInsn(FmlSignatureNoticePatch.ORIGINAL);
        if (varargs) {
            call.visitInsn(Opcodes.ICONST_0);
            call.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
        }
        call.visitMethodInsn(instance ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKESTATIC, owner, name, desc, instance);
        call.visitInsn(Opcodes.RETURN);
        call.visitMaxs(0, 0);
        call.visitEnd();
        writer.visitEnd();
        ClassNode node = new ClassNode();
        new ClassReader(writer.toByteArray()).accept(node, 0);
        return node;
    }

    private static Object message(ClassNode node) {
        for (AbstractInsnNode insn : node.methods.getFirst().instructions) {
            if (insn instanceof LdcInsnNode ldc) {
                return ldc.cst;
            }
        }
        return null;
    }

    private static MethodInsnNode logCall(ClassNode node) {
        for (AbstractInsnNode insn : node.methods.getFirst().instructions) {
            if (insn instanceof MethodInsnNode call) {
                return call;
            }
        }
        return null;
    }
}
