# Dove Agent

A Java agent that lets **Minecraft Forge 1.8.9** run on a **Java 25** runtime with full mod support.

Forge 1.8.9 was built for Java 8. The agent fixes, at runtime and without modifying any jar, every
spot where Java 8 assumptions break on Java 25. It is tested with Forge 11.15.1.2318 (and matches
the same code in 11.15.1.1875), OptiFine HD U L5, and Mixin 0.7 mods (OneConfig). It works with
either Forge's own ASM 5 or a newer ASM swapped onto the class path.

## Building

Requires nothing but a JDK to run Gradle; the build downloads a JDK 25 toolchain if one isn't installed.

```bash
./gradlew build
```

The agent is `build/libs/dove-agent.jar`, the name the Dove launcher expects. (The `-slim` jar is
the same code without its dependencies shaded in; don't use it as the agent.)

## Using

Run Forge 1.8.9 on Java 25 with:

```
-javaagent:/path/to/dove-agent.jar=log=<minecraft dir>/logs/dove-agent.log
--enable-native-access=ALL-UNNAMED
--sun-misc-unsafe-memory-access=allow
-Dfile.encoding=COMPAT
```

No `--add-opens` flags are needed; the agent opens the JDK itself.

Agent options (comma-separated after `=`):

| Option  | Default                       | Meaning                                       |
|---------|-------------------------------|-----------------------------------------------|
| `log`   | console only                  | file to also write the agent log to           |
| `cache` | `<java.io.tmpdir>/dove-agent` | where unpacked Forge binary patches are kept  |
| `debug` | `false`                       | verbose logging (also `-Ddove.debug=true`)    |
| `fastStartup` | `true`                  | `false` turns off the startup shortcuts below |

For local launcher testing, point `DOVE_RUNTIME_DIR` at a folder with this `dove-agent.jar` (and
the launcher's `dove-asm.jar`).

## What it fixes

| Patch | Where | Java 25 problem |
|---|---|---|
| `ModuleOpener` | JDK | Strong encapsulation; LaunchWrapper/Forge/mods reflect into JDK internals |
| `UrlClassLoaderPatch` | LaunchWrapper, FML, Mixin, any mod | System class loader isn't a `URLClassLoader`: casts fail, and reflective `addURL` fails, so FML silently drops mod tweakers |
| `LaunchClassLoaderPatch` | LaunchWrapper | Parent is the bootstrap loader, which can't see platform modules (`java.sql`, `java.scripting`, ...) |
| `SecurityManagerPatch` | FML, any mod | `System.setSecurityManager` throws since Java 24 |
| `ClassPatchManagerPatch` | FML | Pack200 (Forge's binary patch format) removed in Java 14; replaced with Commons Compress and cached on disk |
| `JavaVersionCheckPatch` | FML, any mod | commons-lang3 3.3.2 can't parse Java 25; Forge's BlamingTransformer NPEs on every Java 7/8 mod class |
| `EnumHelperPatch` | Forge | Enum constants built with removed `sun.reflect` API |
| `HolderRefPatch` | FML | `@ObjectHolder` fields written with removed `sun.reflect` API and `Field.modifiers` |
| `FinalFieldPatch` | any mod | The `Field.modifiers` trick for writing static finals |
| `AsmClassReaderPatch` | ASM 5, Mixin's ASM | Refuses class files newer than Java 8 |
| `AsmTypePatch` | ASM 6+ (if swapped in) | Stricter descriptor parsing breaks Forge's EventSubscriptionTransformer, so mod event handlers don't register |
| `javax.annotation.*` | Mixin 0.7 | JSR-250 annotations removed in Java 11; Mixin checks transformers for `@Resource` |

## Faster startup

Work FML does while loading that cannot change the result is skipped (`patches/startup`). With
Dove on Forge 1.8.9 this cuts the time to the title screen by about 1.5-2 seconds, and every class
the game loads comes out identical to what FML alone produces (checked by dumping and comparing
all of them with LaunchWrapper's `legacy.debugClassLoadingSave`).

| Patch | Skips |
|---|---|
| `ForgeTransformerSkipPatch` | SideTransformer, EventSubscriberTransformer and TerminalTransformer re-writing classes that have no `@SideOnly`, no `@SubscribeEvent`, or no exit/halt call |
| `ModScanPatch` | Reading method bodies while scanning mod jars for annotations; a backtracking regex run on every class name |

## Project layout

```
src/main/java/
├── dev/dove/agent/
│   ├── DoveAgent.java        premain entry point; registers patches
│   ├── AgentOptions.java     log= / cache= / debug= options
│   ├── module/               opens JDK packages to all unnamed modules
│   ├── transform/            ClassPatch interface, registry (by name or constant-pool marker), transformer
│   ├── patches/              bytecode patches: launchwrapper/, fml/, asm/, compat/ (patterns found anywhere)
│   ├── hooks/                runtime code that patched bytecode calls into
│   └── util/AgentLog         console + file logger (log4j isn't usable this early)
└── javax/annotation/         Java 8's JSR-250 annotations
```

### Adding a patch

1. Implement `ClassPatch` under `patches/<area>/`.
2. Select classes with `targets()` (internal names) and/or `markers()` (constant-pool strings, for
   patterns that can appear in any mod). Change the `ClassNode` in `apply()`; return `true` if changed.
3. Put runtime logic in a `hooks/` class and call it with `Bytecode.redirectCall` and friends.
4. Register it in `DoveAgent#registerPatches`.

**Gotcha:** the shaded jar relocates every string literal that looks like `org/objectweb/asm/...`
or `org/apache/commons/...`. To name the game's own ASM or commons classes, build the string at
runtime (see `patches/asm/GameAsm`).
