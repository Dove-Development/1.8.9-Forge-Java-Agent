package dev.dove.agent.patches.fml;

import dev.dove.agent.hooks.ReflectionHooks;
import dev.dove.agent.transform.Bytecode;
import dev.dove.agent.transform.ClassPatch;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Set;

/**
 * {@code @ObjectHolder} / {@code @ItemStackHolder} injection. Forge fills mods' static final fields
 * using the same removed Java 8 tricks as EnumHelper: {@code makeWritable} strips {@code FINAL}
 * through {@code Field.modifiers}, and {@code apply()} writes through sun.reflect field accessors.
 *
 * <p>{@code makeWritable} becomes a no-op and the two reflective calls in {@code apply()} go through
 * {@link ReflectionHooks#holderRefInvoke}, which writes the field directly.
 */
public final class HolderRefPatch implements ClassPatch {

    private static final String INVOKE = "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;";

    @Override
    public String name() {
        return "HolderRef";
    }

    @Override
    public Set<String> targets() {
        return Set.of(
                "net/minecraftforge/fml/common/registry/ObjectHolderRef",
                "net/minecraftforge/fml/common/registry/ItemStackHolderRef");
    }

    @Override
    public boolean apply(ClassNode node) {
        MethodNode makeWritable = Bytecode.method(node, "makeWritable", "(Ljava/lang/reflect/Field;)V");
        MethodNode apply = Bytecode.method(node, "apply", "()V");
        if (makeWritable == null || apply == null
                || Bytecode.countCalls(apply, "java/lang/reflect/Method", "invoke", INVOKE) != 2) {
            return false; // not the shape this patch knows; leave it alone
        }
        Bytecode.redirectCall(apply, "java/lang/reflect/Method", "invoke", INVOKE,
                ReflectionHooks.OWNER, "holderRefInvoke",
                "(Ljava/lang/reflect/Method;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");

        InsnList noop = new InsnList();
        noop.add(new InsnNode(Opcodes.RETURN));
        Bytecode.replaceBody(makeWritable, noop);
        return true;
    }
}
