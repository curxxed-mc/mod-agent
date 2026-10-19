package net.curxxed.dev.agent.diagnostic;

import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

public final class DiagnosticAgent {
    private DiagnosticAgent() {}

    public static void premain(String arguments, Instrumentation instrumentation) {
        try {
            String report = report(instrumentation);
            print(report, false);
            writeReport(report);
        } catch (Exception failure) {
            print("[Mod-Agent Diagnostic] Could not complete diagnostic: " + failure
                    + System.lineSeparator(), true);
        }
    }

    private static String report(Instrumentation instrumentation) {
        StringBuilder report = new StringBuilder();
        entry(report, "event", "STARTUP_AGENT_LOADED");
        entry(report, "timestamp", Instant.now().toString());
        String runtimeName = ManagementFactory.getRuntimeMXBean().getName();
        int separator = runtimeName.indexOf('@');
        entry(report, "pid", separator > 0 ? runtimeName.substring(0, separator) : "unknown");
        entry(report, "instrumentation", instrumentation != null ? "provided" : "missing");
        String probe = System.getProperty("modagent.probe");
        entry(report, "modagent.probe.present", Boolean.toString(probe != null));
        entry(report, "modagent.probe.isTrue", Boolean.toString("true".equals(probe)));
        for (String key : new String[]{"java.version", "java.vendor", "java.vm.name", "java.home", "os.name", "os.arch"}) {
            entry(report, key, System.getProperty(key, "unknown"));
        }
        entry(report, "diagnostic.transformers", "none");
        entry(report, "diagnostic.customNativeLibraries", "none");
        report.append(System.lineSeparator());
        return report.toString();
    }

    private static void entry(StringBuilder report, String key, String value) {
        report.append("[Mod-Agent Diagnostic] ").append(key).append('=')
                .append(value.replace('\r', ' ').replace('\n', ' '))
                .append(System.lineSeparator());
    }

    private static void writeReport(String report) {
        try {
            String override = System.getProperty("modagent.diagnostic.log", "");
            Path path = override.isEmpty()
                    ? Paths.get(DiagnosticAgent.class.getProtectionDomain().getCodeSource()
                            .getLocation().toURI()).getParent().resolve("mod-agent-diagnostic.log")
                    : Paths.get(override).toAbsolutePath();
            append(path, report);
            return;
        } catch (Exception failure) {
            print("[Mod-Agent Diagnostic] Primary log unavailable: " + failure
                    + System.lineSeparator(), true);
        }
        try {
            append(Paths.get(System.getProperty("user.home"), ".mod-agent", "mod-agent-diagnostic.log"), report);
        } catch (Exception failure) {
            print("[Mod-Agent Diagnostic] Fallback log unavailable; use console output: " + failure
                    + System.lineSeparator(), true);
        }
    }

    private static void append(Path path, String report) throws java.io.IOException {
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        Files.write(path, report.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        print("[Mod-Agent Diagnostic] log=" + path.toAbsolutePath() + System.lineSeparator(), false);
    }

    private static void print(String message, boolean error) {
        try {
            if (error) System.err.print(message);
            else System.out.print(message);
        } catch (Exception ignored) {}
    }
}
