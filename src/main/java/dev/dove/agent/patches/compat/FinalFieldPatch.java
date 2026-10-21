package dev.dove.agent.patches.compat;

import dev.dove.agent.hooks.ReflectionHooks;
import dev.dove.agent.transform.Bytecode;
import dev.dove.agent.transform.ClassPatch;
import org.objectweb.asm.tree.ClassNode;

import java.util.Set;

/**
 * The Java 8 "remove FINAL via {@code Field.modifiers}" trick, common in mods that replace
 * Minecraft's static final fields. Since Java 12 {@code Field.modifiers} can't be looked up, so the
 * trick throws {@code NoSuchFieldException}.
 *
 * <p>In classes that mention {@code "modifiers"} as a string, the field lookup, the modifier read
 * and write, and {@code Field.set} go through {@link ReflectionHooks}, which writes static finals
 * through Unsafe the way Java 8 effectively did.
 */
public final class FinalFieldPatch implements ClassPatch {

    private static final String FIELD = "java/lang/reflect/Field";

    @Override
    public String name() {
        return "FinalField";
    }

    @Override
    public Set<String> markers() {
        return Set.of("modifiers");
    }

    @Override
    public boolean apply(ClassNode node) {
        if (!Bytecode.hasStringConstant(node, "modifiers")) {
            return false; // a member named "modifiers", not the trick
        }
        String hooks = ReflectionHooks.OWNER;
        int changed = Bytecode.redirectCall(node, "java/lang/Class", "getDeclaredField",
                "(Ljava/lang/String;)Ljava/lang/reflect/Field;",
                hooks, "getDeclaredField", "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/reflect/Field;");
        changed += Bytecode.redirectCall(node, FIELD, "getInt", "(Ljava/lang/Object;)I",
                hooks, "getInt", "(Ljava/lang/reflect/Field;Ljava/lang/Object;)I");
        changed += Bytecode.redirectCall(node, FIELD, "setInt", "(Ljava/lang/Object;I)V",
                hooks, "setInt", "(Ljava/lang/reflect/Field;Ljava/lang/Object;I)V");
        changed += Bytecode.redirectCall(node, FIELD, "set", "(Ljava/lang/Object;Ljava/lang/Object;)V",
                hooks, "set", "(Ljava/lang/reflect/Field;Ljava/lang/Object;Ljava/lang/Object;)V");
        return changed > 0;
    }
}
