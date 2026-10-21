package dev.dove.agent.patches.fml;

import dev.dove.agent.transform.ClassPatch;
import org.objectweb.asm.tree.ClassNode;

import java.util.Set;

/**
 * The Security Manager was permanently disabled in Java 24 (JEP 486):
 * {@code System.setSecurityManager} now always throws UnsupportedOperationException.
 * FML installs its own security manager during startup (to stop mods calling System.exit),
 * which would crash the launch.
 *
 * <p>Fix: strip the {@code System.setSecurityManager} call.
 */
public final class SecurityManagerPatch implements ClassPatch {

    @Override
    public String name() {
        return "SecurityManager";
    }

    @Override
    public Set<String> targets() {
        // TODO: confirm which FML class installs FMLSecurityManager in 1.8.9-11.15.1.2318.
        return Set.of();
    }

    @Override
    public boolean apply(ClassNode node) {
        // TODO: remove INVOKESTATIC java/lang/System.setSecurityManager and pop its argument.
        return false;
    }
}
