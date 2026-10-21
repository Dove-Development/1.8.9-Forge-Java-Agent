package dev.dove.agent.patches.asm;

import dev.dove.agent.transform.Bytecode;
import dev.dove.agent.transform.ClassPatch;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.Set;

/**
 * When ASM 6+ replaces Forge's ASM 5 on the class path: ASM 5's {@code Type.getType} accepted
 * anything, treating an unrecognized descriptor as a method type. ASM 6+ throws instead. Forge's
 * EventSubscriptionTransformer passes internal names to it, so on ASM 9 every event class fails to
 * transform and mod event handlers fail to register.
 *
 * <p>The throw in {@code Type.getTypeInternal} is replaced with ASM 5's behavior.
 */
public final class AsmTypePatch implements ClassPatch {

    private static final String TYPE = GameAsm.TYPE;
    private static final int METHOD_SORT = 11;

    @Override
    public String name() {
        return "AsmType";
    }

    @Override
    public Set<String> targets() {
        return Set.of(TYPE);
    }

    @Override
    public boolean apply(ClassNode node) {
        MethodNode method = Bytecode.method(node, "getTypeInternal", "(Ljava/lang/String;II)L" + TYPE + ";");
        if (method == null || Bytecode.method(node, "<init>", "(ILjava/lang/String;II)V") == null) {
            return false; // ASM 5 or an unknown layout
        }
        AbstractInsnNode throwStart = null;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn.getOpcode() == Opcodes.NEW && ((TypeInsnNode) insn).desc.equals("java/lang/IllegalArgumentException")) {
                throwStart = insn;
            } else if (insn.getOpcode() == Opcodes.ATHROW && throwStart != null) {
                InsnList lenient = new InsnList();
                lenient.add(new TypeInsnNode(Opcodes.NEW, TYPE));
                lenient.add(new InsnNode(Opcodes.DUP));
                lenient.add(new IntInsnNode(Opcodes.BIPUSH, METHOD_SORT));
                lenient.add(new VarInsnNode(Opcodes.ALOAD, 0));
                lenient.add(new VarInsnNode(Opcodes.ILOAD, 1));
                lenient.add(new VarInsnNode(Opcodes.ILOAD, 2));
                lenient.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, TYPE, "<init>", "(ILjava/lang/String;II)V", false));
                lenient.add(new InsnNode(Opcodes.ARETURN));
                method.instructions.insertBefore(throwStart, lenient);
                // Remove the old NEW ... ATHROW sequence.
                AbstractInsnNode current = throwStart;
                while (current != insn) {
                    AbstractInsnNode next = current.getNext();
                    method.instructions.remove(current);
                    current = next;
                }
                method.instructions.remove(insn);
                return true;
            }
        }
        return false;
    }
}
