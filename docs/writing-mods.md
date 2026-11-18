# Writing mods

Badlion has neither Forge nor Mixin. Mods that should run everywhere use an [ASM transformer](#asm-transformer) for their hooks and a [static entrypoint](#static-entrypoint) for startup. On Lunar you can also use Mixin.

<a id="asm-transformer"></a>
## ASM transformer

Your transformer class needs this method:

```java
public static byte[] transform(String internalName, byte[] classBytes, ClassLoader loader)
```

It is called for every game class as it loads. Return the new bytes, or the input unchanged.

Optionally, add one of these. It is called once, before the first `transform`:

```java
public static void init(File modJar, ClassLoader gameLoader, String minecraftClassName)
public static void init(File modJar, ClassLoader gameLoader)
```

The transformer runs in its own class loader, while the hook classes your patches call run from the game's class loader. **They are two separate copies of your classes, so static state set in `init` is not visible to your hooks.** Initialise hook-side state lazily, on first use.

On Badlion, transformers run after Badlion's own class rewriting, so they see its final bytecode, not vanilla. Some methods exist under different names or places there; see [Troubleshooting → Badlion](troubleshooting.md#badlion).

List your transformer's package and any shaded libraries in `exclude` so the agent doesn't remap them.

<a id="static-entrypoint"></a>
## Static entrypoint

Set `entrypoint` to a class with:

```java
public static void init()
```

An optional `public static void preInit()` runs first. Both are called once, on the client thread, after Minecraft has started. On Badlion, that is once its fonts and textures are loaded. Make them safe to call only once, and don't rely on Forge events.

## What the agent handles for you

You don't need to do any of the following in your mod source:

- Set `remap = false` on mixin annotations.
- Keep `@Mixin` priority at or below 100. Higher values are clamped.
- Translate obf, SRG or MCP names. Class, method, field, descriptor and mixin selector references are remapped to the runtime namespace.
- Avoid clashes between your `@Accessor`/`@Invoker` method names and Lunar's own mixin methods on the same class. Clashing names are renamed.
- Give custom Forge `Event` subclasses a no-args constructor.
- Clear Lunar's bake cache. It is cleared on every launch and again when the game quits.
- Instantiate your mod and call its init once `Minecraft.startGame` returns.

## Shipping one JAR for Forge and the agent

Build two outputs. Put `remapJar` (SRG names) in the Forge mods folder. Put `shadowJar` (MCP names) in `agent-mods.json`; the agent remaps it at runtime.
