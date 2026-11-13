package dev.curxxed.agent;

import java.awt.GraphicsEnvironment;
import java.io.File;
import java.util.Arrays;

/**
 * Entry point of the agent jar, for the setup window.
 */
public final class AgentLauncher {
    private AgentLauncher() {}

    public static void main(String[] args) throws Exception {
        File agent = new File(AgentLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (!agent.isFile()) throw new IllegalStateException("Run the shaded agent JAR, not its classes directory.");
        File config = new File(agent.getParentFile(), "agent-mods.json");
        if (!config.isFile()) {
            File directory = agent.getParentFile();
            for (int depth = 0; directory != null && depth < 3; depth++, directory = directory.getParentFile()) {
                File candidate = new File(directory, "agent-mods.json");
                if (candidate.isFile()) { config = candidate; break; }
            }
        }

        if (args.length == 0 && !GraphicsEnvironment.isHeadless()) {
            AgentWindow.show(agent, config); return;
        }

        try {
            run(agent, config, args);
        } catch (Exception e) {
            System.err.println("Error: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
            System.exit(1);
        }
    }

    private static void run(File agent, File config, String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("setup-badlion")) {
            BadlionLauncherSetup.main(agent, config, Arrays.copyOfRange(args, 1, args.length));
            return;
        }

        if (args.length > 0 && args[0].equals("lunar-argument")) {
            if (args.length == 3 && args[1].equals("--config")) {
                config = new File(args[2]);
            } else if (args.length != 1) {
                throw new IllegalArgumentException("Usage: lunar-argument [--config <config.json>]");
            }

            final LunarJvmArgument.Result result = LunarJvmArgument.prepare(agent, config, LunarJvmArgument.defaultFolder());
            if (result.copiedTo != null) {
                System.err.println("The path has a space, which Lunar can't handle. Agent and config copied to " + result.copiedTo);
            }
            System.out.println(result.argument);
            return;
        }
        System.err.println("Usage: java -jar " + agent.getName() + " lunar-argument [--config <config.json>]");
        System.err.println("       java -jar " + agent.getName() + " setup-badlion [--runtime <Badlion Java bin dir>] [--output <dir>]"
                + " [--config <config.json>] [--vm-option <option>] [--log <file>] [--no-settings] [--restore]");
        System.exit(2);
    }
}
