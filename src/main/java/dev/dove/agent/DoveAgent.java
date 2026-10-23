package dev.dove.agent;

import dev.dove.agent.hooks.HybridCpu;
import dev.dove.agent.module.ModuleOpener;
import dev.dove.agent.patches.asm.AsmClassReaderPatch;
import dev.dove.agent.patches.asm.AsmTypePatch;
import dev.dove.agent.patches.compat.FinalFieldPatch;
import dev.dove.agent.patches.compat.JavaVersionCheckPatch;
import dev.dove.agent.patches.compat.SecurityManagerPatch;
import dev.dove.agent.patches.compat.UrlClassLoaderPatch;
import dev.dove.agent.patches.fml.ClassPatchManagerPatch;
import dev.dove.agent.patches.fml.EnumHelperPatch;
import dev.dove.agent.patches.fml.HolderRefPatch;
import dev.dove.agent.patches.launchwrapper.LaunchClassLoaderPatch;
import dev.dove.agent.patches.startup.ForgeTransformerSkipPatch;
import dev.dove.agent.patches.startup.ModScanPatch;
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
    private static volatile AgentOptions options = AgentOptions.parse(null);

    private DoveAgent() {
    }

    public static void premain(String args, Instrumentation inst) {
        start(args, inst);
    }

    public static void agentmain(String args, Instrumentation inst) {
        // Attaching late is only useful for debugging: the classes these patches target are
        // loaded during startup.
        AgentLog.warn("Attached after startup; patches for already-loaded classes will not apply.");
        start(args, inst);
    }

    private static synchronized void start(String args, Instrumentation inst) {
        if (instrumentation != null) {
            AgentLog.warn("Agent already started, ignoring second start.");
            return;
        }
        long startNanos = System.nanoTime();
        instrumentation = inst;
        options = AgentOptions.parse(args);
        AgentLog.init(options.logFile(), options.debug());

        AgentLog.info("Dove agent " + version() + " on Java " + Runtime.version() + " (" + System.getProperty("java.vendor") + ")");
        if (Runtime.version().feature() < 25) {
            AgentLog.warn("Dove targets Java 25; running on " + Runtime.version().feature() + " is untested.");
        }

        ModuleOpener.openAll(inst);
        // premain runs on the thread that becomes Minecraft's client (render) thread.
        HybridCpu.apply(options.hybridCpu());

        PatchRegistry registry = new PatchRegistry();
        registerPatches(registry);
        inst.addTransformer(new DoveTransformer(registry), false);

        AgentLog.info("Registered " + registry.size() + " patches in " + (System.nanoTime() - startNanos) / 1_000_000 + " ms");
    }

    private static void registerPatches(PatchRegistry registry) {
        // LaunchWrapper
        registry.register(new LaunchClassLoaderPatch());
        // ASM on the game class path (ASM 5 from Forge, or ASM 6+ if a launcher swapped it in)
        registry.register(new AsmClassReaderPatch());
        registry.register(new AsmTypePatch());
        // Forge
        registry.register(new ClassPatchManagerPatch());
        registry.register(new EnumHelperPatch());
        registry.register(new HolderRefPatch());
        // Java 8 patterns, wherever they appear (LaunchWrapper, Forge, mods)
        registry.register(new UrlClassLoaderPatch());
        registry.register(new SecurityManagerPatch());
        registry.register(new FinalFieldPatch());
        registry.register(new JavaVersionCheckPatch());
        // Faster loading: skip work FML does that cannot change the result
        if (options.fastStartup()) {
            registry.register(new ForgeTransformerSkipPatch());
            registry.register(new ModScanPatch());
        } else {
            AgentLog.info("Startup shortcuts off (fastStartup=false)");
        }
    }

    public static Instrumentation instrumentation() {
        return instrumentation;
    }

    public static AgentOptions options() {
        return options;
    }

    public static String version() {
        String version = DoveAgent.class.getPackage().getImplementationVersion();
        return version != null ? version : "dev";
    }
}
