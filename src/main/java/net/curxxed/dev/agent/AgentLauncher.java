package net.curxxed.dev.agent;

import java.awt.GraphicsEnvironment;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.swing.*;

/** Child-process-scoped JVM options; no wrapper, global environment edit, or runtime patch. */
public final class AgentLauncher {
    private AgentLauncher() {}

    static String toolOptions(String existing, File agent, File config, List<String> options) {
        String prefix = "-javaagent:" + agent.getAbsolutePath();
        if (existing != null && existing.contains(prefix)) {
            throw new IllegalArgumentException("JAVA_TOOL_OPTIONS already contains this agent; remove the duplicate first.");
        }
        StringBuilder result = new StringBuilder(existing == null ? "" : existing.trim());
        append(result, prefix + "=" + config.getAbsolutePath());
        for (String option : options) append(result, option);
        return result.toString();
    }

    private static void append(StringBuilder result, String option) {
        if (option.indexOf('\n') >= 0 || option.indexOf('\r') >= 0 || option.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("VM options may not contain line breaks or NULs.");
        }
        char quote = option.indexOf('"') < 0 ? '"' : '\'';
        if (option.indexOf(quote) >= 0) throw new IllegalArgumentException("Option contains both quote styles: choose a simpler file path.");
        if (result.length() != 0) result.append(' ');
        result.append(quote).append(option).append(quote);
    }

    public static Process start(File agent, File config, List<String> command, List<String> options)
            throws java.io.IOException {
        if (!agent.isFile() || !config.isFile()) throw new IllegalArgumentException("Agent JAR and configuration must exist.");
        if (command.isEmpty()) throw new IllegalArgumentException("Select a launcher executable.");
        List<String> resolved = new ArrayList<>(command);
        File executable = new File(resolved.get(0));
        if (executable.isDirectory() && executable.getName().endsWith(".app")) {
            File[] candidates = new File(executable, "Contents/MacOS").listFiles(file -> file.isFile() && file.canExecute());
            if (candidates == null || candidates.length != 1) throw new IllegalArgumentException("Select the executable inside the app's Contents/MacOS directory.");
            resolved.set(0, candidates[0].getAbsolutePath());
        }
        ProcessBuilder builder = new ProcessBuilder(resolved).inheritIO();
        Map<String, String> environment = builder.environment();
        environment.put("JAVA_TOOL_OPTIONS", toolOptions(environment.get("JAVA_TOOL_OPTIONS"), agent, config, options));
        return builder.start();
    }

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
        if (args.length == 0 && !GraphicsEnvironment.isHeadless()) { show(agent, config); return; }
        if (args.length > 0 && args[0].equals("setup-badlion")) {
            BadlionLauncherSetup.main(agent, config, Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (args.length == 0 || !args[0].equals("launch")) {
            System.err.println("Usage: java -jar lunar-agent.jar launch --config <config.json> [--vm-option <option>] -- <launcher> [args]");
            System.err.println("       java -jar lunar-agent.jar setup-badlion [--runtime <Badlion Java bin dir>] [--output <dir>]"
                    + " [--config <config.json>] [--vm-option <option>] [--log <file>]");
            System.exit(2); return;
        }
        List<String> options = new ArrayList<>();
        int index = 1;
        while (index < args.length && !args[index].equals("--")) {
            String option = args[index++];
            if (index == args.length) throw new IllegalArgumentException("Missing value for " + option);
            if (option.equals("--config")) config = new File(args[index++]);
            else if (option.equals("--vm-option")) options.add(args[index++]);
            else throw new IllegalArgumentException("Unknown launcher option: " + option);
        }
        if (index == args.length) throw new IllegalArgumentException("Separate the launcher command with --");
        List<String> command = Arrays.asList(Arrays.copyOfRange(args, index + 1, args.length));
        System.out.println("Starting launcher with child-scoped agent options. Close any existing launcher first.");
        int status = start(agent, config, command, options).waitFor();
        if (status != 0) System.exit(status);
    }

    private static void show(File agent, File initialConfig) {
        SwingUtilities.invokeLater(() -> {
            JFrame window = new JFrame("Mod-Agent");
            window.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            JTextField config = new JTextField(initialConfig.getAbsolutePath(), 32);
            JButton configBrowse = new JButton("Browse");
            JPanel configRow = new JPanel(new java.awt.BorderLayout(6, 0));
            configRow.add(new JLabel("Mod configuration:  "), java.awt.BorderLayout.WEST);
            configRow.add(config); configRow.add(configBrowse, java.awt.BorderLayout.EAST);
            configBrowse.addActionListener(event -> choose(window, config));

            JPanel panel = new JPanel();
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
            panel.add(configRow);
            panel.add(javax.swing.Box.createVerticalStrut(10));
            panel.add(badlionPanel(window, agent, config));
            panel.add(javax.swing.Box.createVerticalStrut(10));
            panel.add(lunarArgumentPanel(agent, config));
            panel.add(javax.swing.Box.createVerticalStrut(10));
            panel.add(vanillaPanel(window, agent, config));
            window.setContentPane(panel);
            window.pack(); window.setLocationRelativeTo(null); window.setVisible(true);
        });
    }

    private static JPanel badlionPanel(JFrame window, File agent, JTextField config) {
        JPanel panel = new JPanel(new java.awt.BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createTitledBorder("Badlion (one click)"));
        JButton install = new JButton("Set up Badlion"), restore = new JButton("Undo");
        JLabel status = new JLabel("Quit Lunar fully first. This points Lunar's Badlion Java path at the agent.");
        JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));
        buttons.add(restore); buttons.add(install);
        panel.add(status); panel.add(buttons, java.awt.BorderLayout.EAST);
        java.nio.file.Path output = java.nio.file.Paths.get(System.getProperty("user.home"), ".mod-agent", "badlion-runtime");
        install.addActionListener(event -> {
            install.setEnabled(false); restore.setEnabled(false);
            status.setText("Setting up (compiling launcher on Windows can take a moment)...");
            File selectedConfig = new File(config.getText());
            new Thread(() -> {
                try {
                    java.nio.file.Path runtime = BadlionLauncherSetup.detectRuntime();
                    java.nio.file.Path bin = BadlionLauncherSetup.install(runtime, output, agent, selectedConfig,
                            java.util.Collections.emptyList(), output.resolve("launcher.log"));
                    BadlionLauncherSetup.wireSettings(output, bin);
                    report(status, "Done. Fully quit Lunar (incl. tray), then launch Badlion as normal.");
                } catch (Throwable failure) {
                    report(status, "Could not set up: " + failure.getMessage());
                } finally { reenable(install, restore); }
            }, "Mod-Agent-Badlion-Setup").start();
        });
        restore.addActionListener(event -> {
            install.setEnabled(false); restore.setEnabled(false);
            new Thread(() -> {
                try {
                    BadlionLauncherSetup.restoreSettings(output);
                    report(status, "Lunar's Badlion Java path restored. Quit Lunar fully for it to take effect.");
                } catch (Throwable failure) {
                    report(status, "Could not restore: " + failure.getMessage());
                } finally { reenable(install, restore); }
            }, "Mod-Agent-Badlion-Restore").start();
        });
        return panel;
    }

    private static JPanel lunarArgumentPanel(File agent, JTextField config) {
        JPanel panel = new JPanel(new java.awt.BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createTitledBorder("Lunar (JVM argument)"));
        JTextField argument = new JTextField(32);
        argument.setEditable(false);
        JButton copy = new JButton("Copy JVM argument");
        JLabel status = new JLabel("Paste the result into Lunar: Settings > JVM Arguments (enable Advanced Settings).");
        JPanel row = new JPanel(new java.awt.BorderLayout(6, 0));
        row.add(argument); row.add(copy, java.awt.BorderLayout.EAST);
        panel.add(row); panel.add(status, java.awt.BorderLayout.SOUTH);
        copy.addActionListener(event -> {
            copy.setEnabled(false);
            File selectedConfig = new File(config.getText());
            new Thread(() -> {
                try {
                    LunarJvmArgument.Result result = LunarJvmArgument.prepare(agent, selectedConfig, LunarJvmArgument.defaultFolder());
                    SwingUtilities.invokeLater(() -> {
                        argument.setText(result.argument);
                        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                                .setContents(new java.awt.datatransfer.StringSelection(result.argument), null);
                    });
                    report(status, result.copiedTo == null ? "Copied to clipboard. Paste it into Lunar's JVM Arguments."
                            : "Your path has a space, which Lunar can't handle, so the agent and config were copied to "
                            + result.copiedTo + ". Copied to clipboard. Press again after changing your config.");
                } catch (Throwable failure) {
                    report(status, "Could not prepare: " + failure.getMessage());
                } finally { reenable(copy); }
            }, "Mod-Agent-Lunar-Argument").start();
        });
        return panel;
    }

    private static JPanel vanillaPanel(JFrame window, File agent, JTextField config) {
        JPanel panel = new JPanel(new java.awt.BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createTitledBorder("Vanilla Lunar (launch here)"));
        JTextField executable = new JTextField(26);
        JTextField vmOption = new JTextField(16);
        JButton executableBrowse = new JButton("Browse"), launch = new JButton("Launch");
        JLabel status = new JLabel("Pick Lunar's launcher executable; the agent is passed to the game it starts.");
        JPanel fields = new JPanel(new java.awt.GridLayout(0, 3, 6, 6));
        fields.add(new JLabel("Launcher executable")); fields.add(executable); fields.add(executableBrowse);
        fields.add(new JLabel("Extra VM option")); fields.add(vmOption); fields.add(new JLabel("optional, no quotes"));
        JPanel bottom = new JPanel(new java.awt.BorderLayout(8, 8));
        bottom.add(status); bottom.add(launch, java.awt.BorderLayout.EAST);
        panel.add(fields); panel.add(bottom, java.awt.BorderLayout.SOUTH);
        executableBrowse.addActionListener(event -> choose(window, executable));
        launch.addActionListener(event -> {
            launch.setEnabled(false);
            File selectedConfig = new File(config.getText());
            String selectedExecutable = executable.getText();
            String selectedVmOption = vmOption.getText();
            new Thread(() -> {
                try {
                    start(agent, selectedConfig, java.util.Collections.singletonList(selectedExecutable), selectedVmOption.isEmpty()
                            ? java.util.Collections.emptyList() : java.util.Collections.singletonList(selectedVmOption));
                    report(status, "Started. Check mod-agent.log after launching the game.");
                } catch (Exception failure) {
                    report(status, "Could not launch: " + failure.getMessage());
                } finally { reenable(launch); }
            }, "Mod-Agent-Launcher").start();
        });
        return panel;
    }

    private static void report(JLabel status, String message) {
        SwingUtilities.invokeLater(() -> status.setText(message));
    }

    private static void reenable(JButton... buttons) {
        SwingUtilities.invokeLater(() -> { for (JButton button : buttons) button.setEnabled(true); });
    }

    private static void choose(JFrame parent, JTextField target) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        if (!target.getText().isEmpty()) chooser.setSelectedFile(new File(target.getText()));
        if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) target.setText(chooser.getSelectedFile().getAbsolutePath());
    }
}
