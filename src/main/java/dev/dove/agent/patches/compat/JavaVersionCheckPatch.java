package dev.dove.agent.patches.compat;

import dev.dove.agent.hooks.JavaVersionHooks;
import dev.dove.agent.transform.Bytecode;
import dev.dove.agent.transform.ClassPatch;
import org.objectweb.asm.tree.ClassNode;

import java.util.Set;

/**
 * Calls to commons-lang3's {@code SystemUtils.isJavaVersionAtLeast(JavaVersion)}, which throws on
 * Java 25 with the commons-lang3 Minecraft 1.8.9 ships (see {@link JavaVersionHooks}).
 *
 * <p>Forge's BlamingTransformer calls it for every class compiled for Java 7 or 8, so without this
 * nearly every mod class fails to load.
 */
public final class JavaVersionCheckPatch implements ClassPatch {

    // Built at runtime: the jar's shading would otherwise relocate these org/apache/commons literals.
    private static final String LANG3 = String.join("/", "org", "apache", "commons", "lang3");
    private static final String SYSTEM_UTILS = LANG3 + "/SystemUtils";
    private static final String DESC = "(L" + LANG3 + "/JavaVersion;)Z";

    @Override
    public String name() {
        return "JavaVersionCheck";
    }

    @Override
    public Set<String> markers() {
        return Set.of("isJavaVersionAtLeast");
    }

    @Override
    public boolean apply(ClassNode node) {
        return Bytecode.redirectCall(node, SYSTEM_UTILS, "isJavaVersionAtLeast", DESC,
                JavaVersionHooks.OWNER, "isJavaVersionAtLeast", "(Ljava/lang/Object;)Z") > 0;
    }
}
