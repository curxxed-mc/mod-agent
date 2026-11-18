# Debug properties

Pass these as JVM options (`-D...`) to the game. On Badlion, add them with `setup-badlion --vm-option`.

| Property | Default | Description |
|---|---|---|
| `modagent.log.file` | `mod-agent.log` next to the agent JAR | Write the log to this file instead. |
| `modagent.badlion.inspectOnly` | off | Set to `true` to log the real methods and calls in `Minecraft` and `EntityRenderer` on Badlion without applying any mods. |
| `modagent.badlion.finalStage` | `on` | Set to `off` to disable the Badlion final transformer stage. Only useful for debugging. |

This one is for the setup tool, not the game. Pass it before `-jar`, e.g. `java -Dmodagent.lunar.settings=<file> -jar Mod-Agent-<version>.jar setup-badlion`:

| Property | Default | Description |
|---|---|---|
| `modagent.lunar.settings` | Lunar's `launcher.json` | Path to Lunar's settings file, if it isn't in the default place. |
