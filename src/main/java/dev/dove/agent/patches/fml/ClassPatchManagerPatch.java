package dev.dove.agent.patches.fml;

import dev.dove.agent.hooks.BinPatchHooks;
import dev.dove.agent.transform.Bytecode;
import dev.dove.agent.transform.ClassPatch;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Set;

/**
 * Forge unpacks its binary patches with {@code java.util.jar.Pack200}, removed in Java 14. Without
 * them no Minecraft class gets Forge's changes and the game cannot start.
 *
 * <p>{@code Pack200.newUnpacker()} becomes {@code null} and {@code unpacker.unpack(in, out)} becomes
 * {@link BinPatchHooks#unpack}, which does the same with Commons Compress and caches the result.
 */
public final class ClassPatchManagerPatch implements ClassPatch {

    @Override
    public String name() {
        return "ClassPatchManager";
    }

    @Override
    public Set<String> targets() {
        return Set.of("net/minecraftforge/fml/common/patcher/ClassPatchManager");
    }

    @Override
    public boolean apply(ClassNode node) {
        boolean changed = false;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.equals("java/util/jar/Pack200")
                        && call.name.equals("newUnpacker")) {
                    method.instructions.set(call, new InsnNode(Opcodes.ACONST_NULL));
                    changed = true;
                }
            }
            changed |= Bytecode.redirectCall(method, "java/util/jar/Pack200$Unpacker", "unpack",
                    "(Ljava/io/InputStream;Ljava/util/jar/JarOutputStream;)V",
                    BinPatchHooks.OWNER, "unpack",
                    "(Ljava/lang/Object;Ljava/io/InputStream;Ljava/util/jar/JarOutputStream;)V") > 0;
        }
        return changed;
    }
}
