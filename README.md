# Dove Agent

A Java agent that lets **Minecraft Forge 1.8.9** run on a **Java 25** runtime with full mod support.

Forge 1.8.9 was built for Java 8. On modern Java it breaks because of things like:

- LaunchWrapper/FML assuming the app class loader is a `URLClassLoader` (not true since Java 9)
- Strong encapsulation of JDK internals (mods reflect into `java.lang`, `sun.*`, etc.)
- `System.setSecurityManager` throwing since Java 24 (JEP 486)

The agent fixes these at runtime by opening JDK modules and patching bytecode as classes load, so
no Forge or mod jars need to be modified.

## Building

Requires nothing but a JDK to run Gradle; the build downloads a JDK 25 toolchain if one isn't installed.

```bash
./gradlew build
```

The agent jar is written to `build/libs/dove-agent-<version>.jar`. (The `-slim` jar is the same
code without ASM shaded in; don't use it as the agent.)

## Using

Point your launcher's Forge 1.8.9 profile at a Java 25 runtime and add to the JVM arguments:

```
-javaagent:/path/to/dove-agent-<version>.jar
```

Add `-Ddove.debug=true` for verbose agent logging.

## Project layout

```
src/main/java/dev/dove/agent/
├── DoveAgent.java          premain/agentmain entry point; registers patches
├── module/ModuleOpener     opens JDK packages to all unnamed modules (replaces --add-opens flags)
├── transform/
│   ├── ClassPatch          interface every bytecode patch implements
│   ├── PatchRegistry       maps class names to their patches
│   └── DoveTransformer     ClassFileTransformer that runs the patches (ASM tree API)
├── patches/
│   ├── launchwrapper/      patches for net.minecraft.launchwrapper
│   └── fml/                patches for Forge / FML
└── util/AgentLog           stdout logger (log4j isn't usable this early)
```

### Adding a patch

1. Create a class implementing `ClassPatch` under `patches/<area>/`.
2. Return the internal class names to target from `targets()` and modify the `ClassNode` in `apply()`.
3. Register it in `DoveAgent#registerPatches`.

ASM is relocated to `dev.dove.agent.shadow.asm` in the final jar, so it never conflicts with the
ASM 5 that Forge ships.

## Status

Scaffold only. `LaunchClassPathPatch` and `SecurityManagerPatch` are stubs with TODOs.
