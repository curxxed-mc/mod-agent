# How it works

## 1. `premain` runs before `main()`

The JVM calls the agent's `premain` before anything else. It:

- deletes the Lunar bake cache, so Mixin re-runs on this launch, and registers a shutdown hook that deletes it again on quit;
- reads your `agent-mods.json`;
- scans each mod JAR for `@Mod`-annotated classes;
- registers bytecode transformers;
- encodes the mod list into a system property for later.

## 2. Transformers patch classes as they load

| Transformer | What it does |
|---|---|
| `MinecraftBootstrapTransformer` | Injects the agent bootstrap into the runtime `Minecraft.startGame` method, including obfuscated `ave.am()V` on vanilla-style clients. |
| `RuntimeRemapper` | Translates class, method, field and descriptor references from obf, SRG or MCP into the detected runtime namespace, using `mappings/combined.csv`. It follows the superclass and interface chain, so inherited members resolve too. |
| `MixinAnnotationPatcher` | Remaps mixin string selectors, forces `remap = false`, and clamps `@Mixin` priority to 100, safely below Lunar's own mixins. |
| `AccessorConflictPatcher` | Renames `@Accessor` and `@Invoker` methods on mixin interfaces with a mod-specific suffix, so they don't clash with Lunar's own methods on the same target class. |
| `EventConstructorPatcher` | Adds a no-args constructor to any Forge `Event` subclass that's missing one, so the Forge event bus can instantiate it. |

## 3. On Lunar, the mixin registrar waits for Ichor

A background thread polls every 50 ms until `MixinEnvironment` appears in the loaded classes. It then adds the agent JAR and each mod JAR to Ichor's class loader and registers their mixin configs.

On vanilla-style clients without Mixin, the direct `startGame` transformer is the bootstrap path.

## 4. The bootstrap runs mod init

When `Minecraft.startGame` returns, the agent:

1. reads the mod list from the system property;
2. builds a `URLClassLoader` for the mods;
3. creates each discovered Forge `@Mod` class with its no-argument constructor;
4. calls its `@EventHandler` init methods.

A system-property guard keeps the direct transformer and the Lunar mixin fallback from both firing.

## 5. The `property` gate

Before initialising a mod, the bootstrap checks that the mod's agent property is set. If the mod is loaded normally through FML, the property is never set, the agent does nothing, and Forge's own lifecycle handles init as usual.

## 6. Badlion: load after Badlion's own agent

Badlion ships a native agent (`-agentpath`) that rewrites Minecraft classes as they load. Lunar's Badlion launch accepts no JVM arguments except `-Xmx`.

So `setup-badlion` installs a small launcher as Lunar's Badlion Java path. The launcher adds `-javaagent` as the *last* JVM option, which makes the JVM load this agent after Badlion's. Class-file hooks run in agent load order, so a final transformer stage sees Badlion's rewritten classes and applies mod hooks on top, without them being discarded.

`net.badlion` classes are never patched. Mods are bootstrapped on the client thread once Minecraft's fonts and textures are ready.
