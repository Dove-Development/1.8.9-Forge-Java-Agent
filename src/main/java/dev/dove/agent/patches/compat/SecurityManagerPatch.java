package dev.dove.agent.patches.compat;

import dev.dove.agent.hooks.SecurityHooks;
import dev.dove.agent.transform.Bytecode;
import dev.dove.agent.transform.ClassPatch;
import org.objectweb.asm.tree.ClassNode;

import java.util.Set;

/**
 * {@code System.setSecurityManager} throws since Java 24. FML's tweaker calls it on every launch,
 * and some mods do too; the call becomes a logged no-op.
 */
public final class SecurityManagerPatch implements ClassPatch {

    @Override
    public String name() {
        return "SecurityManager";
    }

    @Override
    public Set<String> markers() {
        return Set.of("setSecurityManager");
    }

    @Override
    public boolean apply(ClassNode node) {
        return Bytecode.redirectCall(node, "java/lang/System", "setSecurityManager", "(Ljava/lang/SecurityManager;)V",
                SecurityHooks.OWNER, "setSecurityManager", "(Ljava/lang/SecurityManager;)V") > 0;
    }
}
