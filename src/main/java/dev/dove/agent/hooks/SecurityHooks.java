package dev.dove.agent.hooks;

import dev.dove.agent.util.AgentLog;

/**
 * The Security Manager was permanently disabled in Java 24 (JEP 486): installing one throws.
 * FML installs one at startup only to stop mods calling {@code System.exit}.
 */
public final class SecurityHooks {

    public static final String OWNER = "dev/dove/agent/hooks/SecurityHooks";

    private SecurityHooks() {
    }

    /** Replaces {@code System.setSecurityManager}. */
    @SuppressWarnings("removal")
    public static void setSecurityManager(SecurityManager manager) {
        AgentLog.info("Skipped installing " + (manager == null ? "null" : manager.getClass().getName())
                + ": Java 24+ has no Security Manager");
    }
}
