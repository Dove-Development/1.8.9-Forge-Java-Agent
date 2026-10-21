package dev.dove.agent.transform;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Small ASM tree helpers shared by the patches.
 */
public final class Bytecode {

    private Bytecode() {
    }

    /** The method with this name and descriptor, or {@code null}. */
    public static MethodNode method(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) {
                return method;
            }
        }
        return null;
    }

    /**
     * Replaces calls to {@code owner.name desc} (any invoke opcode) in {@code method} with a call to
     * the static {@code hookOwner.hookName hookDesc}. The hook must take the receiver (if any) as its
     * first parameter and otherwise match the original call's stack shape.
     *
     * @return the number of calls replaced
     */
    public static int redirectCall(MethodNode method, String owner, String name, String desc,
                                   String hookOwner, String hookName, String hookDesc) {
        int count = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call
                    && call.owner.equals(owner) && call.name.equals(name) && call.desc.equals(desc)) {
                method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, hookOwner, hookName, hookDesc, false));
                count++;
            }
        }
        return count;
    }

    /** Number of calls to {@code owner.name desc} in {@code method}. */
    public static int countCalls(MethodNode method, String owner, String name, String desc) {
        int count = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call
                    && call.owner.equals(owner) && call.name.equals(name) && call.desc.equals(desc)) {
                count++;
            }
        }
        return count;
    }

    /** {@link #redirectCall(MethodNode, String, String, String, String, String, String)} over every method. */
    public static int redirectCall(ClassNode node, String owner, String name, String desc,
                                   String hookOwner, String hookName, String hookDesc) {
        int count = 0;
        for (MethodNode method : node.methods) {
            count += redirectCall(method, owner, name, desc, hookOwner, hookName, hookDesc);
        }
        return count;
    }

    /**
     * Replaces {@code CHECKCAST type} everywhere in the class with a static hook call taking an
     * {@code Object} and returning {@code type}.
     */
    public static int redirectCast(ClassNode node, String type, String hookOwner, String hookName) {
        String hookDesc = "(Ljava/lang/Object;)L" + type + ";";
        int count = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn.getOpcode() == Opcodes.CHECKCAST && ((TypeInsnNode) insn).desc.equals(type)) {
                    method.instructions.set(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, hookOwner, hookName, hookDesc, false));
                    count++;
                }
            }
        }
        return count;
    }

    /** Whether any method loads {@code value} as a string constant ({@code LDC "value"}). */
    public static boolean hasStringConstant(ClassNode node, String value) {
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof LdcInsnNode ldc && value.equals(ldc.cst)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Replaces the whole body of {@code method}. The new body must be straight-line code. */
    public static void replaceBody(MethodNode method, InsnList body) {
        method.instructions.clear();
        method.instructions.add(body);
        method.tryCatchBlocks.clear();
        method.localVariables = null;
        method.visibleLocalVariableAnnotations = null;
        method.invisibleLocalVariableAnnotations = null;
    }
}
