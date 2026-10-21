package dev.dove.agent.patches.launchwrapper;

import dev.dove.agent.transform.ClassPatch;
import org.objectweb.asm.tree.ClassNode;

import java.util.Set;

/**
 * LaunchWrapper's {@code Launch} constructor does
 * {@code ((URLClassLoader) getClass().getClassLoader()).getURLs()} to build LaunchClassLoader's
 * class path. Since Java 9 the app class loader is not a URLClassLoader, so this throws a
 * ClassCastException before Minecraft even starts.
 *
 * <p>Fix: replace that call with URLs built from the {@code java.class.path} system property.
 */
public final class LaunchClassPathPatch implements ClassPatch {

    @Override
    public String name() {
        return "LaunchClassPath";
    }

    @Override
    public Set<String> targets() {
        return Set.of("net/minecraft/launchwrapper/Launch");
    }

    @Override
    public boolean apply(ClassNode node) {
        // TODO: find the CHECKCAST java/net/URLClassLoader + getURLs() in <init> and redirect it
        //  to a static hook that returns the class path as URL[].
        return false;
    }
}
