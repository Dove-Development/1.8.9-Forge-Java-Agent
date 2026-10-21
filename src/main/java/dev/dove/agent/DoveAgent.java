package dev.dove.agent;

import dev.dove.agent.module.ModuleOpener;
import dev.dove.agent.patches.fml.SecurityManagerPatch;
import dev.dove.agent.patches.launchwrapper.LaunchClassPathPatch;
import dev.dove.agent.transform.DoveTransformer;
import dev.dove.agent.transform.PatchRegistry;
import dev.dove.agent.util.AgentLog;

import java.lang.instrument.Instrumentation;

/**
 * Entry point for the Dove agent.
 *
 * <p>Started with {@code -javaagent:dove-agent.jar} before LaunchWrapper's main class runs, so every
 * class Minecraft, LaunchWrapper, Forge and mods define afterwards passes through {@link DoveTransformer}.
 */
public final class DoveAgent {

    private static volatile Instrumentation instrumentation;

    private DoveAgent() {
    }

    public static void premain(String args, Instrumentation inst) {
        start(args, inst);
    }

    public static void agentmain(String args, Instrumentation inst) {
        // Attaching late is mostly useful for debugging; most patches target classes that are
        // already loaded by then, so this will not give full mod support.
        AgentLog.warn("Attached after startup; patches for already-loaded classes will not apply.");
        start(args, inst);
    }

    private static synchronized void start(String args, Instrumentation inst) {
        if (instrumentation != null) {
            AgentLog.warn("Agent already started, ignoring second start.");
            return;
        }
        instrumentation = inst;

        AgentLog.info("Dove agent " + version() + " starting on Java " + Runtime.version());
        if (Runtime.version().feature() < 25) {
            AgentLog.warn("Dove targets Java 25; running on " + Runtime.version().feature() + " is untested.");
        }

        ModuleOpener.openAll(inst);

        PatchRegistry registry = new PatchRegistry();
        registerPatches(registry);
        inst.addTransformer(new DoveTransformer(registry), true);

        AgentLog.info("Registered " + registry.size() + " patch(es).");
    }

    private static void registerPatches(PatchRegistry registry) {
        registry.register(new LaunchClassPathPatch());
        registry.register(new SecurityManagerPatch());
    }

    public static Instrumentation instrumentation() {
        return instrumentation;
    }

    public static String version() {
        String version = DoveAgent.class.getPackage().getImplementationVersion();
        return version != null ? version : "dev";
    }
}
