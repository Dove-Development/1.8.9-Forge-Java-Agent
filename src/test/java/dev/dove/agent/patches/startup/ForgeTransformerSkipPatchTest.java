package dev.dove.agent.patches.startup;

import dev.dove.agent.transform.DoveTransformer;
import dev.dove.agent.transform.PatchRegistry;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Patches a stand-in for FML's SideTransformer whose transform has a branch of its own (so the
 * patched method's frames are checked by the verifier), loads it, and calls it.
 */
class ForgeTransformerSkipPatchTest {

    private static final String SIDE = "net/minecraftforge/fml/common/asm/transformers/SideTransformer";
    private static final byte[] WORKED = {42};

    @Test
    void skipsClassesWithoutTheAnnotationAndRunsForTheRest() throws Exception {
        Object transformer = load(patchedStandIn()).getDeclaredConstructor().newInstance();
        Method transform = transformer.getClass().getMethod("transform", String.class, String.class, byte[].class);

        byte[] plain = annotatedClass(null);
        byte[] sided = annotatedClass("Lnet/minecraftforge/fml/relauncher/SideOnly;");

        assertSame(plain, transform.invoke(transformer, "a", "a", plain));
        assertArrayEquals(WORKED, (byte[]) transform.invoke(transformer, "a", "a", sided));
    }

    /** {@code transform}: returns null for null bytes, else {42} - the "work" a real transformer does. */
    private static byte[] patchedStandIn() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V1_6, Opcodes.ACC_PUBLIC, SIDE, null, "java/lang/Object", null);
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor m = writer.visitMethod(Opcodes.ACC_PUBLIC, "transform", "(Ljava/lang/String;Ljava/lang/String;[B)[B", null, null);
        m.visitCode();
        Label notNull = new Label();
        m.visitVarInsn(Opcodes.ALOAD, 3);
        m.visitJumpInsn(Opcodes.IFNONNULL, notNull);
        m.visitInsn(Opcodes.ACONST_NULL);
        m.visitInsn(Opcodes.ARETURN);
        m.visitLabel(notNull);
        m.visitInsn(Opcodes.ICONST_1);
        m.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_BYTE);
        m.visitInsn(Opcodes.DUP);
        m.visitInsn(Opcodes.ICONST_0);
        m.visitIntInsn(Opcodes.BIPUSH, 42);
        m.visitInsn(Opcodes.BASTORE);
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        writer.visitEnd();

        PatchRegistry registry = new PatchRegistry();
        registry.register(new ForgeTransformerSkipPatch());
        byte[] patched = new DoveTransformer(registry).transform(ForgeTransformerSkipPatchTest.class.getClassLoader(), SIDE, null, null, writer.toByteArray());
        assertNotNull(patched, "patch did not apply");
        return patched;
    }

    private static byte[] annotatedClass(String annotation) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "com/example/Thing", null, "java/lang/Object", null);
        if (annotation != null) {
            writer.visitAnnotation(annotation, true).visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static Class<?> load(byte[] bytes) {
        return new ClassLoader(ForgeTransformerSkipPatchTest.class.getClassLoader()) {
            Class<?> define() {
                return defineClass(null, bytes, 0, bytes.length);
            }
        }.define();
    }
}
