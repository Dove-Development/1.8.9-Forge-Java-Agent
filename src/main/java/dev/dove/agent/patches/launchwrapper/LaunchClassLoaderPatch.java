package dev.dove.agent.patches.launchwrapper;

import dev.dove.agent.transform.Bytecode;
import dev.dove.agent.transform.ClassPatch;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.Set;

/**
 * Two fixes to LaunchWrapper's class loader, in its constructor:
 *
 * <ul>
 *   <li>Its parent is {@code null} (the bootstrap loader). On Java 8 that saw the whole JDK; since
 *   Java 9 modules such as {@code java.sql}, {@code java.scripting} or {@code jdk.crypto.ec} live in
 *   the platform loader instead. The parent becomes the platform loader, which still delegates to
 *   bootstrap first.</li>
 *   <li>The agent's own package is excluded, so classes patched to call the agent's hooks share the
 *   one copy of them in the system class loader instead of loading a second, uninitialized copy.</li>
 * </ul>
 */
public final class LaunchClassLoaderPatch implements ClassPatch {

    private static final String URL_CLASS_LOADER_INIT = "([Ljava/net/URL;Ljava/lang/ClassLoader;)V";

    @Override
    public String name() {
        return "LaunchClassLoader";
    }

    @Override
    public Set<String> targets() {
        return Set.of("net/minecraft/launchwrapper/LaunchClassLoader");
    }

    @Override
    public boolean apply(ClassNode node) {
        MethodNode init = Bytecode.method(node, "<init>", "([Ljava/net/URL;)V");
        if (init == null) {
            return false;
        }

        boolean parentFixed = false;
        for (AbstractInsnNode insn : init.instructions) {
            if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
                    && call.owner.equals("java/net/URLClassLoader") && call.name.equals("<init>")
                    && call.desc.equals(URL_CLASS_LOADER_INIT) && call.getPrevious().getOpcode() == Opcodes.ACONST_NULL) {
                init.instructions.set(call.getPrevious(), new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "java/lang/ClassLoader", "getPlatformClassLoader", "()Ljava/lang/ClassLoader;", false));
                parentFixed = true;
                break;
            }
        }
        if (!parentFixed) {
            return false;
        }

        for (AbstractInsnNode insn : init.instructions.toArray()) {
            if (insn.getOpcode() == Opcodes.RETURN) {
                InsnList exclude = new InsnList();
                exclude.add(new VarInsnNode(Opcodes.ALOAD, 0));
                exclude.add(new LdcInsnNode("dev.dove.agent."));
                exclude.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, "addClassLoaderExclusion",
                        "(Ljava/lang/String;)V", false));
                init.instructions.insertBefore(insn, exclude);
            }
        }
        return true;
    }
}
