package dev.dove.agent.patches.asm;

import dev.dove.agent.transform.Bytecode;
import dev.dove.agent.transform.ClassPatch;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.ClassFileFormatVersion;
import java.util.Set;

/**
 * Forge 1.8.9 ships ASM 5, which refuses class files newer than Java 8 ({@code version > 52}).
 * Mixin and FML read class bytes of superclasses, which can be JDK classes; on Java 25 those are
 * version 69. ASM 5 parses them fine (new attributes are skipped as unknown); only the version
 * check is in the way, so it is raised to what this JVM supports.
 *
 * <p>Also applied to the ASM 5.2 that Mixin 0.7 bundles under its own package. ASM 6+ has no such
 * check against current class files, so this does nothing to it.
 */
public final class AsmClassReaderPatch implements ClassPatch {

    private static final int JAVA_8 = 52;

    @Override
    public String name() {
        return "AsmClassReader";
    }

    @Override
    public Set<String> targets() {
        return Set.of(GameAsm.CLASS_READER, GameAsm.MIXIN_CLASS_READER);
    }

    @Override
    public boolean apply(ClassNode node) {
        MethodNode init = Bytecode.method(node, "<init>", "([BII)V");
        if (init == null) {
            return false;
        }
        // ASM 5: readShort(off + 6) > Opcodes.V1_8 -> bipush 52 right after the readShort call.
        for (AbstractInsnNode insn : init.instructions) {
            if (insn instanceof MethodInsnNode call && call.name.equals("readShort")
                    && call.getNext() instanceof IntInsnNode limit && limit.getOpcode() == Opcodes.BIPUSH
                    && limit.operand == JAVA_8) {
                init.instructions.set(limit, new IntInsnNode(Opcodes.SIPUSH, ClassFileFormatVersion.latest().major()));
                return true;
            }
        }
        return false;
    }
}
