package dev.dove.agent.transform;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import java.util.Set;

/**
 * A bytecode patch applied to classes as they are defined.
 *
 * <p>A patch selects classes by name ({@link #targets()}), by content ({@link #markers()}), or both.
 * Marker matching is what lets one patch fix a pattern wherever it appears, in Forge and in mods.
 */
public interface ClassPatch {

    /** Short name used in logs. */
    String name();

    /** Internal names of the classes to patch, e.g. {@code net/minecraft/launchwrapper/Launch}. */
    default Set<String> targets() {
        return Set.of();
    }

    /**
     * Constant-pool strings (class names, member names, string literals) that make a class a
     * candidate for this patch. Checked against the raw class bytes, so it costs no parsing.
     */
    default Set<String> markers() {
        return Set.of();
    }

    /**
     * Applies the patch.
     *
     * @return {@code true} if the class was changed and needs to be rewritten
     */
    boolean apply(ClassNode node);

    /**
     * {@link ClassWriter} flags used when this patch changed the class. The default only recomputes
     * max stack/locals, which is enough for patches that keep the stack shape of what they replace.
     */
    default int writerFlags() {
        return ClassWriter.COMPUTE_MAXS;
    }
}
