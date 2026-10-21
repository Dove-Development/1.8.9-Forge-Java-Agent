package dev.dove.agent.patches.compat;

import dev.dove.agent.hooks.ClassPathHooks;
import dev.dove.agent.transform.Bytecode;
import dev.dove.agent.transform.ClassPatch;
import org.objectweb.asm.tree.ClassNode;

import java.util.Set;

/**
 * Wherever code assumes the system class loader is a URLClassLoader:
 *
 * <ul>
 *   <li>{@code (URLClassLoader) loader} casts go through {@link ClassPathHooks#castToUrlClassLoader},
 *   which hands back a class-path view for the system loader. LaunchWrapper's {@code Launch} does
 *   this to build its class path.</li>
 *   <li>In classes that reflect on {@code "addURL"}, {@code Method.invoke} goes through
 *   {@link ClassPathHooks#invoke}, which turns {@code addURL} on the system loader into a real class
 *   path append. FML loads mod tweakers this way; on unpatched Java 25 it fails silently and the
 *   mod's tweaker (often Mixin) never runs.</li>
 * </ul>
 */
public final class UrlClassLoaderPatch implements ClassPatch {

    private static final String URL_CLASS_LOADER = "java/net/URLClassLoader";

    @Override
    public String name() {
        return "URLClassLoader";
    }

    @Override
    public Set<String> markers() {
        return Set.of(URL_CLASS_LOADER);
    }

    @Override
    public boolean apply(ClassNode node) {
        int changed = Bytecode.redirectCast(node, URL_CLASS_LOADER, ClassPathHooks.OWNER, "castToUrlClassLoader");
        if (Bytecode.hasStringConstant(node, "addURL")) {
            changed += Bytecode.redirectCall(node, "java/lang/reflect/Method", "invoke",
                    "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",
                    ClassPathHooks.OWNER, "invoke",
                    "(Ljava/lang/reflect/Method;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");
        }
        return changed > 0;
    }
}
