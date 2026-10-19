# lunar-agent

> **This project is not a user-friendly tool. It is a low-level JVM instrumentation framework that requires deep knowledge of Minecraft, Mixin, and Lunar/Badlion client internals.**
> Incorrect use can corrupt game files, cause undiagnosable crashes, or permanently break other mods. No support will be provided for misconfigured setups.

---

A Java agent that injects mods into Lunar Client and Badlion Client 1.8.9 at runtime, bypassing the clients' own mod discovery entirely. It also works on vanilla 1.8.9. Includes automatic obf/SRG/MCP remapping from `combined.csv`, mixin annotation patching, direct `Minecraft.startGame` bootstrapping, and bake cache invalidation. It is pure Java: no native libraries.

You drop a mod JAR in. It works. That's the goal.

### Supported clients

| Client | How the agent is loaded | Forge `@Mod` | Mixin | ASM transformer | Static entrypoint |
|---|---|---|---|---|---|
| Lunar Client | JVM argument in Lunar's settings | no (no Forge classes) | yes | yes | yes |
| Lunar Client + Forge module | JVM argument in Lunar's settings | yes | yes | yes | yes |
| Badlion Client (launched from Lunar) | `setup-badlion` launcher | no | no | yes | yes |
| Vanilla 1.8.9 | JVM argument in your launcher | no | no | yes | yes |

Badlion has neither Forge nor Mixin, so mods targeting it use an [ASM transformer](#asm-transformer) for their hooks and a [static entrypoint](#static-entrypoint) for startup.

---

## Important: Forge mod compatibility

Vanilla Lunar Client does not ship any Forge classes. Forge mods will only work when running the **Lunar Client + Forge module**, which provides FML and the Forge event bus. Attempting to inject a Forge mod into plain Lunar Client will fail at class resolution.

---

## How it works

1. **`premain` fires before `main()`** — The JVM calls the agent's `premain` before anything else. It deletes the Lunar bake cache so Mixin re-runs on the next launch, reads your `agent-mods.json`, scans each mod JAR for `@Mod` annotated classes, registers bytecode transformers, and encodes the mod list into a system property for later.

2. **Bytecode transformers patch mod classes on load** — Transformers run on every mod class as it loads:
   - `MinecraftBootstrapTransformer` — injects the agent bootstrap into the runtime `Minecraft.startGame` method, including obfuscated `ave.am()V` on vanilla-style clients.
   - `RuntimeRemapper` — translates class, method, field, and descriptor references from obf, SRG, or MCP into the detected runtime namespace using `mappings/combined.csv`.
   - `MixinAnnotationPatcher` — remaps mixin string selectors, forces `remap = false`, and clamps `@Mixin` priority to 100, safely below Lunar's own mixins.
   - `EventConstructorPatcher` — injects a synthetic no-args constructor into any `Event` subclass that's missing one, so the Forge event bus can instantiate it.

3. **Mixin registrar waits for Ichor when Mixin exists** — On Lunar, a background thread polls every 50ms until `MixinEnvironment` appears in the loaded classes. It then adds the agent JAR and each mod JAR to Ichor's classloader and registers their mixin configs. On vanilla-style clients without Mixin, the direct `startGame` transformer is the bootstrap path.

4. **The shared bootstrap fires mod init** — When `Minecraft.startGame` returns, the agent reads the mod list from the system property, builds a `URLClassLoader`, instantiates each discovered Forge `@Mod` class, and calls the `@EventHandler` init methods. A system-property guard keeps the direct transformer and Lunar mixin fallback from firing twice.

5. **The system property gates Forge mod init** — Before initialising a Forge `@Mod`, the bootstrap checks that the mod's agent property is set. If the mod is loaded normally through FML, the property is never set, the agent does nothing, and Forge's own lifecycle handles init as usual.

6. **Badlion: load after Badlion's own agent** — Badlion ships a native agent (`-agentpath`) that rewrites Minecraft classes as they load, and Lunar's Badlion launch accepts no JVM arguments except `-Xmx`. So `setup-badlion` installs a small launcher as Lunar's Badlion Java path. It adds `-javaagent` as the *last* JVM option, which makes the JVM load this agent after Badlion's. Class-file hooks run in agent load order, so a final transformer stage sees Badlion's rewritten classes and applies mod hooks on top, without them being discarded. `net.badlion` classes are never patched. Mods are bootstrapped on the client thread once Minecraft's fonts and textures are ready.

---

## Setup

### 1. Build the agent JAR

```
./gradlew shadowJar
```

The shadow plugin shades and relocates ASM so it doesn't conflict with Lunar's own copy. The output `MANIFEST.MF` declares:

```
Premain-Class: net.curxxed.dev.agent.AgentBootstrap
Agent-Class:   net.curxxed.dev.agent.AgentBootstrap
Can-Redefine-Classes:    true
Can-Retransform-Classes: true
```

This is already configured in `build.gradle.kts`. Don't use `./gradlew jar` — the plain JAR doesn't have ASM shaded in and will crash on startup.

### 2. Create your config file

Create `agent-mods.json` anywhere on your system. It holds a `mods` array with one object per mod; add more objects to inject several mods. All values are strings.

```json
{
  "mods": [
    {
      "jar": "your-mod.jar",
      "property": "yourmod.agent.injected"
    }
  ]
}
```

#### Fields

| Field | Required | Description |
|---|---|---|
| `jar` | yes | Path to the mod JAR. Relative paths resolve next to `agent-mods.json`. A JAR that doesn't exist is skipped. |
| `property` | no | System property the agent sets to `true` at startup. Your mod can check it to know it was loaded by the agent; Forge-style and entrypoint init is skipped when it is named but unset. |
| `mixin` | no | Mixin config file at the root of the JAR (e.g. `mixins.yourmod.json`). Registered once the client's Mixin environment exists, so it **only works on Lunar**. |
| `asm` | no | Your own bytecode transformer class (see [ASM transformer](#asm-transformer)). Works on Lunar, vanilla and Badlion. |
| `entrypoint` | no | Class with a static `init()` to call at startup (see [Static entrypoint](#static-entrypoint)). Replaces Forge `@Mod` discovery for this JAR. |
| `exclude` | no | Comma-separated class-name prefixes, in `/` form, that the agent must not remap or patch, e.g. `com/example/yourmod/asm/,com/example/yourmod/libs/`. Use it for your transformer and shaded libraries. |
| `remapLoaders` | no | Comma-separated class names of your mod's own class loaders, e.g. `com.example.yourmod.PluginClassLoader`. Classes those loaders define at runtime are remapped too. Only needed if your mod loads extra code itself. |

#### Ways to load a mod

Pick the style your mod is built for. They can be combined; for example, `asm` + `entrypoint` is the usual setup for Badlion.

**1. Forge `@Mod` (default).** With no `entrypoint`, the agent scans the JAR for classes annotated with `@Mod`, creates them with their no-argument constructor once `Minecraft.startGame` finishes, and calls their `@EventHandler` methods for `FMLPreInitializationEvent`, `FMLInitializationEvent` and `FMLPostInitializationEvent`, in that order. The mod needs Forge classes at runtime, so this only works on the Lunar Client + Forge module.

```json
{ "jar": "your-forge-mod.jar", "property": "yourmod.agent.injected" }
```

**2. Mixin.** Add `mixin` to register the mod's Mixin config. The agent forces `remap = false`, remaps selectors to the runtime names and keeps priorities below Lunar's own mixins. Lunar only.

```json
{ "jar": "your-mod.jar", "mixin": "mixins.yourmod.json", "property": "yourmod.agent.injected" }
```

<a id="asm-transformer"></a>
**3. ASM transformer.** Add `asm` with the fully qualified name of a class that has:

```java
public static byte[] transform(String internalName, byte[] classBytes, ClassLoader loader)
```

It is called for every game class as it loads; return the new bytes, or the input unchanged. Optionally add one of these, called once before the first `transform`:

```java
public static void init(File modJar, ClassLoader gameLoader, String minecraftClassName)
public static void init(File modJar, ClassLoader gameLoader)
```

The transformer runs in its own class loader, while the hook classes your patches call run from the game's class loader. **They are two separate copies of your classes, so static state set in `init` is not visible to your hooks.** Initialise hook-side state lazily, on first use. On Badlion, transformers run after Badlion's own class rewriting, so they see its final bytecode, not vanilla.

```json
{
  "jar": "your-mod.jar",
  "asm": "com.example.yourmod.asm.YourTransformer",
  "exclude": "com/example/yourmod/asm/,com/example/yourmod/libs/",
  "property": "yourmod.agent.injected"
}
```

<a id="static-entrypoint"></a>
**4. Static entrypoint.** For mods that aren't Forge mods, set `entrypoint` to a class with `public static void init()`. An optional `public static void preInit()` runs first. Both are called once, on the client thread, after Minecraft has started (on Badlion, once its fonts and textures are loaded). Make them safe to call only once; don't rely on Forge events.

```json
{ "jar": "your-mod.jar", "entrypoint": "com.example.yourmod.YourMod", "property": "yourmod.agent.injected" }
```

#### Full example (Badlion-ready mod)

```json
{
  "mods": [
    {
      "jar": "your-mod.jar",
      "property": "yourmod.agent.injected",
      "entrypoint": "com.example.yourmod.YourMod",
      "asm": "com.example.yourmod.asm.YourTransformer",
      "exclude": "com/example/yourmod/asm/,com/example/yourmod/libs/",
      "remapLoaders": "com.example.yourmod.PluginClassLoader"
    }
  ]
}
```

### 3. Install into your client

#### Lunar Client

In the Lunar Client launcher go to **Settings → JVM Arguments** and add:

```
-javaagent:C:\Users\<User>\Documents\lunar-agent\lunar-agent.jar=C:\Users\<User>\Documents\lunar-agent\agent-mods.json
```

Replace both paths with wherever you placed the files.

**Enable Advanced Settings first, otherwise the JVM Arguments field won't be visible.**

#### Badlion Client (through Lunar)

The JVM Arguments field doesn't work for Badlion (Lunar only passes `-Xmx` to it), so the agent installs a small launcher instead:

1. In Lunar's settings, turn on **Use Badlion Services**, then launch Badlion once so Lunar downloads Badlion's Java runtime.
2. Fully quit Lunar, including the tray icon. Lunar rewrites its settings when it closes and would undo the next step.
3. Double-click `lunar-agent.jar` and press **Set up Badlion**, or run:

   ```
   java -jar lunar-agent.jar setup-badlion --config agent-mods.json
   ```

4. Launch Badlion from Lunar as usual. In `mod-agent.log` (next to the agent JAR), `JVM agent load order` should list `javaagent:lunar-agent.jar` after Badlion's `agentpath:` entry, followed by `Badlion final stage enabled`.

Setup finds Badlion's Java runtime, installs the launcher in `~/.mod-agent/badlion-runtime`, and points Lunar's Badlion Java path (`settings.badlion.javaPath` in `launcher.json`) at it. It backs up `launcher.json` first and changes nothing else. On Linux and macOS the launcher is a bash script; on Windows it is compiled from C# by the .NET Framework compiler that ships with Windows 10/11.

To undo, press **Undo** or run `java -jar lunar-agent.jar setup-badlion --restore`, then delete `~/.mod-agent/badlion-runtime`.

`setup-badlion` options:

| Option | Description |
|---|---|
| `--config <file>` | Your `agent-mods.json` (defaults to one found next to the agent JAR). |
| `--runtime <dir>` | Badlion's Java `bin` directory, if it isn't found automatically. |
| `--output <dir>` | Where to install the launcher (default `~/.mod-agent/badlion-runtime`). |
| `--vm-option -D...` | Extra system property for the game; repeatable. |
| `--log <file>` | Launcher log file (default `<output>/launcher.log`). |
| `--no-settings` | Install the launcher but don't edit Lunar's settings; set the printed `bin` path yourself. |
| `--restore` | Put Lunar's Badlion Java path back to what it was before setup. |

---

## What the agent handles automatically

You do not need to do any of the following in your mod source:

- `remap = false` on every mixin annotation
- `priority` clamped to 100 on every `@Mixin`
- obf/SRG/MCP name translation on class, method, field, descriptor, and mixin selector references
- No-args constructor on custom `Event` subclasses
- Bake cache invalidation on every launch
- Mod instantiation and init call on the first game tick



## Troubleshooting

**`[Mod-Agent] JAR not found, skipping`** — The path in `agent-mods.json` is wrong or the file doesn't exist at that location.

**`[Mod-Agent] Error in mixin registrar`** — The mixin config filename doesn't match what's at the root of the JAR, or the JAR wasn't built with the config on its classpath root.

**`[Mod-Agent] No mods to bootstrap`** — No `@Mod` classes were found during premain scanning. Check that your mod has the annotation and that the JAR path in `agent-mods.json` is correct. Also check you're running `shadowJar` and deploying the right output JAR. If ur mod mixins itself into startGame then you can safely ignore this warning

**Mod init fails with NPE on field access** — `Unsafe.allocateInstance` bypasses the constructor. Move all field initialisation into `init()`.

**Forge mod classes not found on vanilla Lunar** — Vanilla Lunar Client does not ship Forge classes. Forge mods only work with the Lunar Client + Forge module. Use standalone mods that don't depend on Forge.

**Works on Lunar but breaks on vanilla Forge** — Make sure `remapJar` is what you're dropping into the Forge mods folder, not `shadowJar`. The `shadowJar` output has MCP names; Forge expects SRG names.

**Injections silently do nothing** — Check that `mappings/combined.csv` is present in the agent JAR. If the agent logs `Mappings not found`, remapping isn't running.

**Mixin conflicts with Lunar** — Switch from `@Overwrite` to `@Inject` or `@Redirect` (although not always).

**`agent library failed to init` on startup** — You used `./gradlew jar` instead of `./gradlew shadowJar`. Always use `shadowJar`.

### Badlion

**Badlion isn't listed in Lunar** — Badlion is opt-in. Turn on **Use Badlion Services** in Lunar's settings (enable Advanced Settings if you can't see it).

**`Could not find Badlion's Java runtime`** — Launch Badlion once from Lunar so its Java runtime gets downloaded, or pass `--runtime` with the `bin` directory that contains its `java`/`java.exe`.

**`The .NET Framework 4 C# compiler (csc.exe) was not found`** (Windows) — It ships with Windows 10/11 but can be removed. Enable **.NET Framework 4.8** in *Turn Windows features on or off*, then run setup again.

**No `premain fired` in `mod-agent.log` after setup** — Lunar was still running during setup and overwrote its settings on exit. Quit Lunar fully, including the tray icon, and run setup again.

**`WARNING: a native agent loads after this Java agent`** — Something added `-javaagent` before Badlion's own agent (for example an old wrapper or a JVM argument), so Badlion rewrites classes after your hooks and discards them. Remove other `-javaagent` entries and launch through the `setup-badlion` launcher only.

**Hook matches nothing on Badlion but works on Lunar/vanilla** — Badlion rewrites some Minecraft code: for example, keybind handling is moved from `Minecraft.runTick` into its own `runInputTick` method, and some GL calls go through Badlion's own wrapper class. Target the methods that actually exist at runtime. Launch once with the JVM option `-Dmodagent.badlion.inspectOnly=true` (via `--vm-option`) to log the real methods and calls in `Minecraft` and `EntityRenderer` without applying any mods.

**`NullPointerException` in your hooks on Badlion** — Your ASM transformer and your hooks run from two separate copies of your classes (see [ASM transformer](#asm-transformer)). State set up in the transformer's `init` doesn't exist on the hook side; initialise it lazily when it's first used.

**Mixins or Forge mods do nothing on Badlion** — Badlion has no Mixin or Forge. Use an `asm` transformer plus a static `entrypoint`.

**Removing the agent from Badlion** — Press **Undo** in the agent window or run `setup-badlion --restore`. That restores Lunar's previous Badlion Java path.
