package dev.dove.agent.patches.startup;

import dev.dove.agent.hooks.StartupHooks;
import dev.dove.agent.transform.Bytecode;
import dev.dove.agent.transform.ClassPatch;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.Map;
import java.util.Set;

/**
 * Every class the game loads goes through each of FML's class transformers, and most of them read
 * the whole class with ASM and write it out again even when they have nothing to change in it.
 * Each patched transformer now returns the class as it was when a look at its constant pool shows
 * there is nothing for it to do ({@link StartupHooks}):
 * <ul>
 *   <li>SideTransformer: no {@code @SideOnly} annotation</li>
 *   <li>EventSubscriberTransformer: no {@code @SubscribeEvent} annotation</li>
 *   <li>TerminalTransformer: no method named exit or halt referenced</li>
 * </ul>
 * Otherwise they run exactly as before. DeobfuscationTransformer is left alone: which classes it
 * cannot change is not visible from the bytes alone - Minecraft's own packaged classes, such as
 * MinecraftServer, have obfuscated members too.
 */
public final class ForgeTransformerSkipPatch implements ClassPatch {

    private static final String TRANSFORMERS = "net/minecraftforge/fml/common/asm/transformers/";
    private static final String TRANSFORM = "(Ljava/lang/String;Ljava/lang/String;[B)[B";
    private static final Map<String, String> HOOKS = Map.of(
            TRANSFORMERS + "SideTransformer", "skipSideTransformer",
            TRANSFORMERS + "EventSubscriberTransformer", "skipEventSubscriberTransformer",
            TRANSFORMERS + "TerminalTransformer", "skipTerminalTransformer");

    @Override
    public String name() {
        return "ForgeTransformerSkip";
    }

    @Override
    public Set<String> targets() {
        return HOOKS.keySet();
    }

    @Override
    public boolean apply(ClassNode node) {
        MethodNode transform = Bytecode.method(node, "transform", TRANSFORM);
        String hook = HOOKS.get(node.name);
        if (transform == null || hook == null) {
            return false;
        }
        // if (StartupHooks.skipX(bytes)) return bytes;
        LabelNode work = new LabelNode();
        InsnList check = new InsnList();
        check.add(new VarInsnNode(Opcodes.ALOAD, 3));
        check.add(new MethodInsnNode(Opcodes.INVOKESTATIC, StartupHooks.OWNER, hook, "([B)Z", false));
        check.add(new JumpInsnNode(Opcodes.IFEQ, work));
        check.add(new VarInsnNode(Opcodes.ALOAD, 3));
        check.add(new InsnNode(Opcodes.ARETURN));
        check.add(work);
        if ((node.version & 0xFFFF) >= Opcodes.V1_6) {
            // Same locals as on entry, empty stack. Any frames after it were relative to the entry
            // frame, which this one equals, so they stay valid.
            check.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        }
        transform.instructions.insert(check);
        return true;
    }
}
