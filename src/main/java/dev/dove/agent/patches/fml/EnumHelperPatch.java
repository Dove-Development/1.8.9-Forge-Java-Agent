package dev.dove.agent.patches.fml;

import dev.dove.agent.hooks.ReflectionHooks;
import dev.dove.agent.transform.Bytecode;
import dev.dove.agent.transform.ClassPatch;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.Set;

/**
 * Forge's {@code EnumHelper} (used by mods to add armor materials, creature types, ...) builds enum
 * constants with {@code sun.reflect.ReflectionFactory} and writes {@code $VALUES} after removing
 * {@code FINAL} through {@code Field.modifiers}. None of that exists on Java 25.
 *
 * <p>Three method bodies are replaced; the rest of EnumHelper (argument checks, {@code $VALUES}
 * handling, enum cache clearing) runs unchanged:
 * <ul>
 *   <li>{@code setup()} only marks itself done (it looked up the removed sun.reflect API)</li>
 *   <li>{@code makeEnum(...)} calls {@link ReflectionHooks#makeEnum}</li>
 *   <li>{@code setFailsafeFieldValue(...)} calls {@link ReflectionHooks#setField}</li>
 * </ul>
 */
public final class EnumHelperPatch implements ClassPatch {

    private static final String MAKE_ENUM = "(Ljava/lang/Class;Ljava/lang/String;I[Ljava/lang/Class;[Ljava/lang/Object;)Ljava/lang/Enum;";
    private static final String SET_FIELD = "(Ljava/lang/reflect/Field;Ljava/lang/Object;Ljava/lang/Object;)V";

    @Override
    public String name() {
        return "EnumHelper";
    }

    @Override
    public Set<String> targets() {
        return Set.of("net/minecraftforge/common/util/EnumHelper");
    }

    @Override
    public boolean apply(ClassNode node) {
        MethodNode setup = Bytecode.method(node, "setup", "()V");
        MethodNode makeEnum = Bytecode.method(node, "makeEnum", MAKE_ENUM);
        MethodNode setField = Bytecode.method(node, "setFailsafeFieldValue", SET_FIELD);
        if (setup == null || makeEnum == null || setField == null) {
            return false;
        }

        InsnList setupBody = new InsnList();
        setupBody.add(new InsnNode(Opcodes.ICONST_1));
        setupBody.add(new FieldInsnNode(Opcodes.PUTSTATIC, node.name, "isSetup", "Z"));
        setupBody.add(new InsnNode(Opcodes.RETURN));
        Bytecode.replaceBody(setup, setupBody);

        InsnList makeEnumBody = new InsnList();
        makeEnumBody.add(new VarInsnNode(Opcodes.ALOAD, 0));
        makeEnumBody.add(new VarInsnNode(Opcodes.ALOAD, 1));
        makeEnumBody.add(new VarInsnNode(Opcodes.ILOAD, 2));
        makeEnumBody.add(new VarInsnNode(Opcodes.ALOAD, 3));
        makeEnumBody.add(new VarInsnNode(Opcodes.ALOAD, 4));
        makeEnumBody.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ReflectionHooks.OWNER, "makeEnum",
                "(Ljava/lang/Class;Ljava/lang/String;I[Ljava/lang/Class;[Ljava/lang/Object;)Ljava/lang/Object;", false));
        makeEnumBody.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Enum"));
        makeEnumBody.add(new InsnNode(Opcodes.ARETURN));
        Bytecode.replaceBody(makeEnum, makeEnumBody);

        InsnList setFieldBody = new InsnList();
        setFieldBody.add(new VarInsnNode(Opcodes.ALOAD, 0));
        setFieldBody.add(new VarInsnNode(Opcodes.ALOAD, 1));
        setFieldBody.add(new VarInsnNode(Opcodes.ALOAD, 2));
        setFieldBody.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ReflectionHooks.OWNER, "setField", SET_FIELD, false));
        setFieldBody.add(new InsnNode(Opcodes.RETURN));
        Bytecode.replaceBody(setField, setFieldBody);
        return true;
    }
}
