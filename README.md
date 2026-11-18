# mod-agent

> Incorrect use can corrupt game files, cause undiagnosable crashes, or permanently break other mods. No support will be provided for misconfigured setups.

A Java agent that injects mods into Lunar Client and Badlion Client 1.8.9 at runtime, bypassing the clients' own mod discovery entirely. It also works on vanilla 1.8.9. It is pure Java: no native libraries.

It includes:

- automatic obf/SRG/MCP remapping from `combined.csv`;
- mixin annotation patching;
- direct `Minecraft.startGame` bootstrapping;
- bake cache invalidation.

You drop a mod JAR in. It works. That's the goal.

> **Note:** Lunar Client now loads 1.8.9 Forge mods itself on **Lunar + Forge**, so add Forge mods through Lunar instead. The agent is for Badlion Client, vanilla 1.8.9, and non-Forge (ASM, Mixin or entrypoint) mods on Lunar.

## Supported clients

| Client | How the agent is loaded | Mixin | ASM transformer | Static entrypoint |
|---|---|---|---|---|
| Lunar Client | JVM argument in Lunar's settings | yes | yes | yes |
| Badlion Client (launched from Lunar) | `setup-badlion` launcher | no | yes | yes |
| Vanilla 1.8.9 | JVM argument in your launcher | no | yes | yes |

## Quick start

1. Build the agent with `./gradlew shadowJar`.
2. Create `agent-mods.json`:

   ```json
   { "mods": [ { "jar": "your-mod.jar", "property": "yourmod.agent.injected" } ] }
   ```

3. Double-click `build/libs/Mod-Agent-<version>.jar`. Press **Copy JVM argument** for Lunar or vanilla, or **Set up Badlion** for Badlion.

## Docs

- [Installing](docs/installing.md): Lunar, Badlion and vanilla setup.
- [Configuration](docs/configuration.md): `agent-mods.json` fields and the ways to load a mod.
- [Writing mods](docs/writing-mods.md): transformer and entrypoint contracts.
- [How it works](docs/how-it-works.md): what happens from `premain` to mod init.
- [Troubleshooting](docs/troubleshooting.md): log messages and common errors.
- [Debug properties](docs/debug-properties.md): `-Dmodagent.*` options.

## License

Copyright (c) 2026 curxxed and tatp. All rights reserved. No use, copying, modification or redistribution without written permission; see [LICENSE](LICENSE).
