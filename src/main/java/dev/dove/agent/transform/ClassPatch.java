package dev.dove.agent.transform;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import java.util.Set;

/**
 * A bytecode patch applied to one or more classes as they are defined.
 */
public interface ClassPatch {

    /** Short name used in logs. */
    String name();

    /** Internal names of the classes to patch, e.g. {@code net/minecraft/launchwrapper/Launch}. */
    Set<String> targets();

    /**
     * Applies the patch.
     *
     * @return {@code true} if the class was changed and needs to be rewritten
     */
    boolean apply(ClassNode node);

    /**
     * {@link ClassWriter} flags used when this patch changed the class. The default only recomputes
     * max stack/locals; override with {@link ClassWriter#COMPUTE_FRAMES} if the patch changes control
     * flow (be aware frame computation may need to load other classes).
     */
    default int writerFlags() {
        return ClassWriter.COMPUTE_MAXS;
    }
}
