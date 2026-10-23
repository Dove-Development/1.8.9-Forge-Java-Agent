package dev.dove.agent.patches.fml;

import dev.dove.agent.transform.ClassPatch;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Set;

/**
 * FML's sanity check reads the certificates of Forge's own jar and, finding none, logs "FML appears
 * to be missing any signature data. This is not a good thing" as an error. On Java 25 there are
 * never any: Forge's jar is signed with a 2017 DSA signature, which Java no longer accepts, so the
 * jar counts as unsigned. FML does nothing else with the answer.
 *
 * <p>The message now says that, at info level. The check itself is unchanged.
 */
public final class FmlSignatureNoticePatch implements ClassPatch {

    static final String TARGET = "net/minecraftforge/fml/common/asm/FMLSanityChecker";
    static final String ORIGINAL = "FML appears to be missing any signature data. This is not a good thing";
    static final String NOTICE = "FML's signature was not checked: Java 25 no longer accepts the signature algorithm Forge's jar was signed with";

    // 1.8.9 logs through FMLRelaunchLog, 1.12.2 through log4j (built at runtime, as the jar's shading
    // relocates org/apache literals).
    private static final String RELAUNCH_LOG = "net/minecraftforge/fml/relauncher/FMLRelaunchLog";
    private static final String LOG4J_LOGGER = String.join("/", "org", "apache", "logging", "log4j", "Logger");

    @Override
    public String name() {
        return "FmlSignatureNotice";
    }

    @Override
    public Set<String> targets() {
        return Set.of(TARGET);
    }

    @Override
    public boolean apply(ClassNode node) {
        boolean changed = false;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof LdcInsnNode ldc && ORIGINAL.equals(ldc.cst)) {
                    MethodInsnNode log = nextCall(insn);
                    if (log == null || !lowerToInfo(log)) {
                        continue; // not the logging call this patch knows: leave the class alone
                    }
                    ldc.cst = NOTICE;
                    changed = true;
                }
            }
        }
        return changed;
    }

    /** FMLRelaunchLog.severe and Logger.error take the same arguments as their info. */
    private static boolean lowerToInfo(MethodInsnNode call) {
        if (call.owner.equals(RELAUNCH_LOG) && call.name.equals("severe")) {
            call.name = "info";
            return true;
        }
        if (call.owner.equals(LOG4J_LOGGER) && call.name.equals("error")) {
            call.name = "info";
            return true;
        }
        return false;
    }

    private static MethodInsnNode nextCall(AbstractInsnNode from) {
        for (AbstractInsnNode insn = from.getNext(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode call) {
                return call;
            }
        }
        return null;
    }
}
