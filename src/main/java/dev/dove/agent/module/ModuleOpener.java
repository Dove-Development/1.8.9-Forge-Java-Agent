package dev.dove.agent.module;

import dev.dove.agent.util.AgentLog;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Opens JDK internals that LaunchWrapper, Forge and many 1.8.9 mods reflect into.
 *
 * <p>This replaces the long list of {@code --add-opens ...=ALL-UNNAMED} JVM flags. Opening to
 * <em>all</em> unnamed modules matters because LaunchClassLoader (where Minecraft and mods live)
 * has its own unnamed module, which doesn't exist yet when the agent starts.
 */
public final class ModuleOpener {

    /** module name -> packages to open to every unnamed module. Extend as mods need more. */
    private static final Map<String, List<String>> OPENS = Map.of(
            "java.base", List.of(
                    "java.io",
                    "java.lang",
                    "java.lang.invoke",
                    "java.lang.reflect",
                    "java.net",
                    "java.nio",
                    "java.security",
                    "java.util",
                    "java.util.concurrent",
                    "java.util.jar",
                    "java.util.zip",
                    "jdk.internal.loader",
                    "jdk.internal.misc",
                    "jdk.internal.reflect",
                    "sun.nio.ch",
                    "sun.security.util"
            ),
            "java.desktop", List.of(
                    "java.awt",
                    "sun.awt",
                    "sun.font"
            )
    );

    private ModuleOpener() {
    }

    public static void openAll(Instrumentation inst) {
        Module self = ModuleOpener.class.getModule();

        // Step 1: open java.lang to ourselves so we can reach Module#implAddOpensToAllUnnamed.
        Module javaBase = Object.class.getModule();
        inst.redefineModule(javaBase, Set.of(), Map.of(), Map.of("java.lang", Set.of(self)), Set.of(), Map.of());

        Method addOpensToAllUnnamed;
        try {
            addOpensToAllUnnamed = Module.class.getDeclaredMethod("implAddOpensToAllUnnamed", String.class);
            addOpensToAllUnnamed.setAccessible(true);
        } catch (ReflectiveOperationException | RuntimeException e) {
            AgentLog.error("Could not access Module#implAddOpensToAllUnnamed; JDK internals stay closed.", e);
            return;
        }

        // Step 2: open each configured package to every unnamed module.
        int opened = 0;
        for (Map.Entry<String, List<String>> entry : OPENS.entrySet()) {
            Module module = ModuleLayer.boot().findModule(entry.getKey()).orElse(null);
            if (module == null) {
                AgentLog.debug("Module " + entry.getKey() + " not present, skipping.");
                continue;
            }
            for (String pkg : entry.getValue()) {
                if (!module.getPackages().contains(pkg)) {
                    AgentLog.debug("Package " + pkg + " not in " + entry.getKey() + ", skipping.");
                    continue;
                }
                try {
                    addOpensToAllUnnamed.invoke(module, pkg);
                    opened++;
                } catch (ReflectiveOperationException e) {
                    AgentLog.error("Failed to open " + entry.getKey() + "/" + pkg, e);
                }
            }
        }
        AgentLog.info("Opened " + opened + " JDK package(s) to unnamed modules.");
    }
}
