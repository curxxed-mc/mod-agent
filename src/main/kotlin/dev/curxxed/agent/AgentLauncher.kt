package dev.curxxed.agent

import dev.curxxed.agent.badlion.BadlionLauncherSetup
import dev.curxxed.agent.lunar.LunarJvmArgument
import java.awt.GraphicsEnvironment
import java.io.File
import kotlin.system.exitProcess

/**
 * Entry point of the agent jar, for the setup window.
 */
object AgentLauncher {

    @JvmStatic
    fun main(args: Array<String>) {
        val agent = File(AgentLauncher::class.java.protectionDomain.codeSource.location.toURI())
        check(agent.isFile) { "Run the shaded agent JAR, not its classes directory." }
        val config = findConfig(agent)

        if (args.isEmpty() && !GraphicsEnvironment.isHeadless()) {
            AgentWindow.show(agent, config)
            return
        }

        try {
            run(agent, config, args)
        } catch (e: Exception) {
            System.err.println("Error: " + (e.message ?: e.toString()))
            exitProcess(1)
        }
    }

    /// agent-mods.json next to the agent, else in one of the 3 folders above it.
    private fun findConfig(agent: File): File {
        val beside = File(agent.parentFile, "agent-mods.json")
        if (beside.isFile) return beside
        return generateSequence(agent.parentFile) { it.parentFile }
            .take(3)
            .map { File(it, "agent-mods.json") }
            .firstOrNull { it.isFile } ?: beside
    }

    private fun run(agent: File, defaultConfig: File, args: Array<String>) {
        when (args.firstOrNull()) {
            "setup-badlion" -> BadlionLauncherSetup.main(agent, defaultConfig, args.copyOfRange(1, args.size))

            "lunar-argument" -> {
                val config = when {
                    args.size == 3 && args[1] == "--config" -> File(args[2])
                    args.size == 1 -> defaultConfig
                    else -> throw IllegalArgumentException("Usage: lunar-argument [--config <config.json>]")
                }
                val result = LunarJvmArgument.prepare(agent, config, LunarJvmArgument.defaultFolder())
                if (result.copiedTo != null) {
                    System.err.println("The path has a space, which Lunar can't handle. Agent and config copied to ${result.copiedTo}")
                }
                println(result.argument)
            }

            else -> {
                System.err.println("Usage: java -jar ${agent.name} lunar-argument [--config <config.json>]")
                System.err.println("       java -jar ${agent.name} setup-badlion [--runtime <Badlion Java bin dir>] [--output <dir>]"
                        + " [--config <config.json>] [--vm-option <option>] [--log <file>] [--no-settings] [--restore]")
                exitProcess(2)
            }
        }
    }
}
