# Configuration

The agent reads one config file, `agent-mods.json`, which can live anywhere on your system. Its path goes after the `=` in the `-javaagent` argument (see [Installing](installing.md)).

The file holds a `mods` array with one object per mod. Add more objects to inject several mods. All values are strings.

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

## Fields

| Field | Required | Description |
|---|---|---|
| `jar` | yes | Path to the mod JAR. Relative paths resolve next to `agent-mods.json`. A JAR that doesn't exist is skipped. |
| `property` | no | System property the agent sets to `true` at startup. Your mod can check it to know it was loaded by the agent. When named but unset, Forge-style and entrypoint init is skipped. |
| `mixin` | no | Mixin config file at the root of the JAR (e.g. `mixins.yourmod.json`). It is registered once the client's Mixin environment exists, so it **only works on Lunar**. |
| `asm` | no | Your own bytecode transformer class (see [ASM transformer](writing-mods.md#asm-transformer)). Works on Lunar, vanilla and Badlion. |
| `entrypoint` | no | Class with a static `init()` to call at startup (see [Static entrypoint](writing-mods.md#static-entrypoint)). Replaces Forge `@Mod` discovery for this JAR. |
| `exclude` | no | Comma-separated class-name prefixes, in `/` form, that the agent must not remap or patch, e.g. `com/example/yourmod/asm/,com/example/yourmod/libs/`. Use it for your transformer and shaded libraries. |
| `remapLoaders` | no | Comma-separated class names of your mod's own class loaders, e.g. `com.example.yourmod.PluginClassLoader`. Classes those loaders define at runtime are remapped too. Only needed if your mod loads extra code itself. |

## Ways to load a mod

| Style | Lunar | Lunar + Forge | Badlion | Vanilla |
|---|---|---|---|---|
| Forge `@Mod` | no | not needed: add it through Lunar | no | no |
| Mixin | yes | yes | no | no |
| ASM transformer | yes | yes | yes | yes |
| Static entrypoint | yes | yes | yes | yes |

Pick the style your mod is built for. Styles can be combined; for example, `asm` + `entrypoint` is the usual setup for Badlion.

### 1. Forge `@Mod` (default)

This is used when there is no `entrypoint`. The agent scans the JAR for classes annotated with `@Mod`. Once `Minecraft.startGame` finishes, it creates them with their no-argument constructor. Then it calls their `@EventHandler` methods for `FMLPreInitializationEvent`, `FMLInitializationEvent` and `FMLPostInitializationEvent`, in that order.

The mod needs Forge classes at runtime, so this only works on Lunar + Forge, where Lunar now loads Forge mods itself. Prefer Lunar's built-in support; this style is only kept for existing setups.

```json
{ "jar": "your-forge-mod.jar", "property": "yourmod.agent.injected" }
```

### 2. Mixin

Add `mixin` to register the mod's Mixin config. The agent forces `remap = false`, remaps selectors to the runtime names and keeps priorities below Lunar's own mixins. Lunar only.

```json
{ "jar": "your-mod.jar", "mixin": "mixins.yourmod.json", "property": "yourmod.agent.injected" }
```

### 3. ASM transformer

Add `asm` with the fully qualified name of your transformer class. The class needs a `transform` method; see [Writing mods](writing-mods.md#asm-transformer) for the exact signatures.

```json
{
  "jar": "your-mod.jar",
  "asm": "com.example.yourmod.asm.YourTransformer",
  "exclude": "com/example/yourmod/asm/,com/example/yourmod/libs/",
  "property": "yourmod.agent.injected"
}
```

### 4. Static entrypoint

For mods that aren't Forge mods, set `entrypoint` to a class with `public static void init()`. See [Writing mods](writing-mods.md#static-entrypoint).

```json
{ "jar": "your-mod.jar", "entrypoint": "com.example.yourmod.YourMod", "property": "yourmod.agent.injected" }
```

## Full example (Badlion-ready mod)

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
