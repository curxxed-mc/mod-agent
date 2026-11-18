# Troubleshooting

The agent logs to `mod-agent.log` next to the agent JAR (or `~/.mod-agent/mod-agent.log` if it can't write there). Every line is tagged `[Mod-Agent]`.

## Log messages

**`ASM: jar not found, skipping` / `Mixin mod JAR not found, skipping`**: the `jar` path in `agent-mods.json` is wrong, or the file doesn't exist at that location. Relative paths resolve next to `agent-mods.json`.

**`Mixin config not found in JAR`**: the `mixin` value doesn't match a file at the root of the JAR.

**`Error in mixin registrar`**: usually the mixin config filename doesn't match what's at the root of the JAR, or the JAR wasn't built with the config on its classpath root.

**`No mods to bootstrap.`**: no `@Mod` classes were found during premain scanning and no `entrypoint` is set. Check that your mod has the annotation, that the JAR path is correct, and that you're deploying the `shadowJar` output. If your mod mixes into `startGame` itself, you can ignore this.

**`Mappings not found`**: `mappings/combined.csv` is missing from the agent JAR, so remapping isn't running and injections silently do nothing. Rebuild with `./gradlew shadowJar`.

**`ASM transformer has no supported init method, skipping`**: your `init` doesn't match one of the [supported signatures](writing-mods.md#asm-transformer).

## Startup errors

**Game won't start: `Error opening zip file or JAR manifest missing : C:\Users\my`**

A path in your JVM argument contains a space, for example a Windows username like `my name`. Lunar splits the JVM Arguments field on every space and doesn't support quotes. So `-javaagent:C:\Users\my name\Mod-Agent-1.0.0.jar=...` reaches Java cut off at the space, and adding quotes doesn't help.

To fix it, press **Copy JVM argument** in the agent's window. It copies the agent and config to a folder without spaces and gives you a working argument. Or move `Mod-Agent-<version>.jar` and `agent-mods.json` to a folder with no spaces anywhere in its path, such as `C:\mod-agent\`, and update the JVM argument yourself.

Paths *inside* `agent-mods.json` may contain spaces. Badlion setup is not affected.

**`agent library failed to init`**: if it comes after `Error opening zip file`, see the entry above. Otherwise the JAR wasn't built with `./gradlew shadowJar`, so ASM isn't shaded in.

**Game crashes after removing the JVM argument**: a stale Lunar bake cache. The agent clears it on quit, but if the game was killed, launch once more with the agent, or delete every `bake.zip` under `~/.lunarclient/offline/multiver/cache`.

## Mod problems

**`NoSuchMethodException: <init>()` when the mod loads**: the agent creates `@Mod` classes with their no-argument constructor. Add one.

**Forge mod classes not found on plain Lunar**: plain Lunar doesn't ship Forge classes. Run Lunar + Forge and add the mod through Lunar itself (no agent needed), or use a mod that doesn't depend on Forge.

**Works on Lunar but breaks on Forge**: put `remapJar` in the Forge mods folder, not `shadowJar`. The `shadowJar` output has MCP names; Forge expects SRG names.

**Mixin conflicts with Lunar**: switching from `@Overwrite` to `@Inject` or `@Redirect` often helps.

## Badlion

**Badlion isn't listed in Lunar**: Badlion is opt-in. Turn on **Use Badlion Services** in Lunar's settings (enable Advanced Settings if you can't see it).

**`Could not find Badlion's Java runtime`**: launch Badlion once from Lunar so its Java runtime gets downloaded, or pass `--runtime` with the `bin` directory that contains its `java`/`java.exe`.

**Setup picked the wrong Java**: with several runtimes, setup uses Lunar's existing Badlion Java path if set, otherwise the newest Java 8 under Badlion's `Data` folder (for example `jre1.8.0_202` over `jre1.8.0_51_…`). To use another one, run `setup-badlion --runtime "<Badlion Data>\<jre folder>\bin"`.

**`The .NET Framework 4 C# compiler (csc.exe) was not found`** (Windows): it ships with Windows 10/11 but can be removed. Enable **.NET Framework 4.8** in *Turn Windows features on or off*, then run setup again.

**No `premain fired` in `mod-agent.log` after setup**: Lunar was still running during setup and overwrote its settings on exit. Quit Lunar fully, including the tray icon, and run setup again.

**`WARNING: a native agent loads after this Java agent`**: something added `-javaagent` before Badlion's own agent, such as an old wrapper or a JVM argument. Badlion then rewrites classes after your hooks and discards them. Remove other `-javaagent` entries and launch through the `setup-badlion` launcher only.

**Hook matches nothing on Badlion but works on Lunar or vanilla**: Badlion rewrites some Minecraft code. For example, keybind handling moves from `Minecraft.runTick` into its own `runInputTick` method, and some GL calls go through Badlion's own wrapper class. Target the methods that actually exist at runtime. To see them, launch once with `-Dmodagent.badlion.inspectOnly=true` (via `--vm-option`); see [Debug properties](debug-properties.md).

**`NullPointerException` in your hooks on Badlion**: your ASM transformer and your hooks run from two separate copies of your classes (see [ASM transformer](writing-mods.md#asm-transformer)). State set up in the transformer's `init` doesn't exist on the hook side; initialise it lazily on first use.

**Mixins or Forge mods do nothing on Badlion**: Badlion has no Mixin or Forge. Use an `asm` transformer plus a static `entrypoint`.

**Removing the agent from Badlion**: press **Undo** in the agent window or run `setup-badlion --restore`. That restores Lunar's previous Badlion Java path.
