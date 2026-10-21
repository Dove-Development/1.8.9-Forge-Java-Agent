package dev.dove.agent.transform;

import dev.dove.agent.util.AgentLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.List;

/**
 * Routes every class definition to the patches registered for it.
 *
 * <p>Classes defined by LaunchWrapper's LaunchClassLoader (Minecraft, Forge, mods) also come through
 * here, after LaunchWrapper's own {@code IClassTransformer}s have already run on them.
 */
public final class DoveTransformer implements ClassFileTransformer {

    private final PatchRegistry registry;

    public DoveTransformer(PatchRegistry registry) {
        this.registry = registry;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null) {
            return null;
        }
        List<ClassPatch> patches = registry.patchesFor(className);
        if (patches.isEmpty()) {
            return null;
        }

        try {
            ClassNode node = new ClassNode();
            new ClassReader(classfileBuffer).accept(node, 0);

            boolean changed = false;
            int flags = 0;
            for (ClassPatch patch : patches) {
                if (patch.apply(node)) {
                    changed = true;
                    flags |= patch.writerFlags();
                    AgentLog.info("Applied " + patch.name() + " to " + className);
                } else {
                    AgentLog.debug(patch.name() + " made no changes to " + className);
                }
            }
            if (!changed) {
                return null;
            }

            ClassWriter writer = new ClassWriter(flags);
            node.accept(writer);
            return writer.toByteArray();
        } catch (Throwable t) {
            // Exceptions thrown from a transformer are silently swallowed by the JVM, so log them here.
            AgentLog.error("Failed to patch " + className + "; leaving it unchanged.", t);
            return null;
        }
    }
}
