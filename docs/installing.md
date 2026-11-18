# Installing

## Build the agent JAR

```
./gradlew shadowJar
```

This produces `build/libs/Mod-Agent-<version>.jar`. Pass `-PagentJarName=<name>.jar` to change the file name. The shadow plugin shades and relocates ASM so it doesn't conflict with Lunar's own copy. The output `MANIFEST.MF` declares:

```
Premain-Class: dev.curxxed.agent.AgentBootstrap
Agent-Class:   dev.curxxed.agent.AgentBootstrap
Can-Redefine-Classes:    true
Can-Retransform-Classes: true
```

This is already configured in `build.gradle.kts`. The plain `jar` task is disabled, because a JAR without ASM shaded in crashes on startup.

Next, write an `agent-mods.json` (see [Configuration](configuration.md)), then install the agent into your client.

## Lunar Client

The easiest way is to double-click `Mod-Agent-<version>.jar`:

1. Under **1. Select your mods**, check that it found your `agent-mods.json`. It shows *Ready: 1 mod (...)* or what is wrong.
2. Under **2. Install into your client**, pick **Lunar** and press **Copy JVM argument**.
3. Paste the result into Lunar's **Settings → JVM Arguments**.

**Enable Advanced Settings first, otherwise the JVM Arguments field won't be visible.**

Press the button again whenever you change your config or update the agent.

If the agent or config path contains a space, the agent and config are first copied to a folder without spaces, and the argument points there. That folder is `C:\mod-agent` on Windows and `~/.mod-agent/lunar` elsewhere. Lunar can't handle spaces in this field.

From a script or installer, this prints the same argument as its last output line:

```
java -jar Mod-Agent-<version>.jar lunar-argument --config agent-mods.json
```

To write it by hand instead, add this under **Settings → JVM Arguments**, replacing both paths with wherever you placed the files:

```
-javaagent:C:\Users\<User>\Documents\mod-agent\Mod-Agent-1.0.0.jar=C:\Users\<User>\Documents\mod-agent\agent-mods.json
```

## Vanilla 1.8.9

The **Vanilla** tab in the agent window gives the same argument. Add it to the JVM arguments of your 1.8.9 profile in the Minecraft Launcher.

## Badlion Client (through Lunar)

The JVM Arguments field doesn't work for Badlion, because Lunar only passes `-Xmx` to it. The agent installs a small launcher instead:

1. In Lunar's settings, turn on **Use Badlion Services**, then launch Badlion once so Lunar downloads Badlion's Java runtime.
2. Fully quit Lunar, including the tray icon. Lunar rewrites its settings when it closes and would undo the next step.
3. Double-click `Mod-Agent-<version>.jar` and press **Set up Badlion**, or run:

   ```
   java -jar Mod-Agent-<version>.jar setup-badlion --config agent-mods.json
   ```

4. Launch Badlion from Lunar as usual.

To check that it worked, open `mod-agent.log` next to the agent JAR. Under `JVM agent load order`, `javaagent:Mod-Agent-<version>.jar` should come after Badlion's `agentpath:` entry, followed by `Badlion final stage enabled`.

### What setup changes

- **Finds Badlion's Java runtime.** Badlion keeps every runtime it has downloaded. Setup uses the one Lunar's Badlion Java path already names, otherwise the newest Java 8, and shows which.
- **Installs the launcher** in `~/.mod-agent/badlion-runtime`. On Linux and macOS it is a bash script. On Windows it is compiled from C# by the .NET Framework compiler that ships with Windows 10/11.
- **Points Lunar's Badlion Java path at the launcher.** That is `settings.badlion.javaPath` in `launcher.json`. Setup backs up `launcher.json` first and changes nothing else.

To undo, press **Undo** or run `java -jar Mod-Agent-<version>.jar setup-badlion --restore`, then delete `~/.mod-agent/badlion-runtime`.

### `setup-badlion` options

| Option | Description |
|---|---|
| `--config <file>` | Your `agent-mods.json` (defaults to one found next to the agent JAR). |
| `--runtime <dir>` | Badlion's Java `bin` directory, if it isn't found automatically or you want a different one than setup picks. |
| `--output <dir>` | Where to install the launcher (default `~/.mod-agent/badlion-runtime`). |
| `--vm-option -D...` | Extra system property for the game; repeatable. |
| `--log <file>` | Launcher log file (default `<output>/launcher.log`). |
| `--no-settings` | Install the launcher but don't edit Lunar's settings; set the printed `bin` path yourself. |
| `--restore` | Put Lunar's Badlion Java path back to what it was before setup. |

## Command-line errors

On failure, every command prints one `Error: <reason>` line and exits with status 1, so installers can show the reason directly.
