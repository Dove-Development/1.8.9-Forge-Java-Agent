package dev.dove.agent.module;

import dev.dove.agent.util.AgentLog;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

/**
 * Opens every JDK package to every unnamed module, as Java 8 effectively did.
 *
 * <p>LaunchWrapper, Forge and many 1.8.9 mods reflect into JDK internals. This replaces the long
 * list of {@code --add-opens ...=ALL-UNNAMED} flags a launcher would otherwise need. Opening to
 * <em>all</em> unnamed modules matters because LaunchClassLoader (where Minecraft and mods live)
 * has its own unnamed module, which doesn't exist yet when the agent starts.
 */
public final class ModuleOpener {

    private ModuleOpener() {
    }

    public static void openAll(Instrumentation inst) {
        // Open java.lang to ourselves first, so we can reach Module#implAddOpensToAllUnnamed.
        Module self = ModuleOpener.class.getModule();
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

        int opened = 0;
        for (Module module : ModuleLayer.boot().modules()) {
            for (String pkg : module.getPackages()) {
                try {
                    addOpensToAllUnnamed.invoke(module, pkg);
                    opened++;
                } catch (ReflectiveOperationException e) {
                    AgentLog.debug("Failed to open " + module.getName() + "/" + pkg + ": " + e);
                }
            }
        }
        AgentLog.info("Opened " + opened + " JDK packages to unnamed modules.");
    }
}
