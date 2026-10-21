package dev.dove.agent.patches.startup;

import dev.dove.agent.hooks.StartupHooks;
import dev.dove.agent.patches.asm.GameAsm;
import dev.dove.agent.transform.ClassPatch;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Set;

/**
 * FML reads every class of every mod jar to find its annotations ({@code @Mod},
 * {@code @EventHandler}, ...). Two parts of that do far more work than the answer needs:
 * <ul>
 *   <li>ASMModParser reads each class with ASM including all of its method bodies, though it only
 *   listens for annotations - which sit outside the code. It now tells ASM to skip the code
 *   ({@code SKIP_CODE}), which reports every annotation just the same.</li>
 *   <li>ModContainerFactory tests each class name against {@code .*(\.|)(mod\_[^\s$]+)$} for
 *   ModLoader-era {@code mod_} classes, a regex that backtracks over the whole name from every
 *   position; it now asks {@link StartupHooks#find}, which answers that one without the regex.</li>
 * </ul>
 */
public final class ModScanPatch implements ClassPatch {

    private static final String PARSER = "net/minecraftforge/fml/common/discovery/asm/ASMModParser";
    private static final String FACTORY = "net/minecraftforge/fml/common/ModContainerFactory";
    private static final int SKIP_CODE = 1;

    @Override
    public String name() {
        return "ModScan";
    }

    @Override
    public Set<String> targets() {
        return Set.of(PARSER, FACTORY);
    }

    @Override
    public boolean apply(ClassNode node) {
        return node.name.equals(PARSER) ? skipCode(node) : fastModLoaderTest(node);
    }

    private static boolean skipCode(ClassNode node) {
        String accept = "(L" + GameAsm.PACKAGE + "/ClassVisitor;I)V";
        boolean changed = false;
        for (MethodNode method : node.methods) {
            if (!method.name.equals("<init>")) {
                continue;
            }
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(GameAsm.CLASS_READER)
                        && call.name.equals("accept") && call.desc.equals(accept)
                        && call.getPrevious().getOpcode() == Opcodes.ICONST_0) {
                    method.instructions.set(call.getPrevious(), new InsnNode(Opcodes.ICONST_0 + SKIP_CODE));
                    changed = true;
                }
            }
        }
        return changed;
    }

    /** {@code pattern.matcher(name).find()} becomes {@code StartupHooks.find(pattern, name)}. */
    private static boolean fastModLoaderTest(ClassNode node) {
        boolean changed = false;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode matcher && matcher.owner.equals("java/util/regex/Pattern")
                        && matcher.name.equals("matcher")
                        && matcher.getNext() instanceof MethodInsnNode find && find.owner.equals("java/util/regex/Matcher")
                        && find.name.equals("find") && find.desc.equals("()Z")) {
                    method.instructions.remove(matcher);
                    method.instructions.set(find, new MethodInsnNode(Opcodes.INVOKESTATIC, StartupHooks.OWNER, "find",
                            "(Ljava/util/regex/Pattern;Ljava/lang/CharSequence;)Z", false));
                    changed = true;
                }
            }
        }
        return changed;
    }
}
