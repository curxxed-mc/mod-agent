package net.curxxed.dev.agent;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Writes to console as well as its own log file, which will be created in the current directory the agent jar exists in.
 */
// TODO: Make this an option in the json or remove. My idea would be to have it as a hidden entry, useful for people if they need to debug.
public final class AgentLog {

    private static final Path LOG_FILE;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    static {
        Path selected = null;
        final String override = System.getProperty("modagent.log.file", "");
        try {
            final Path jarDir = Paths.get(AgentLog.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent();
            Path besideJar = jarDir.resolve("mod-agent.log");
            selected = !override.isEmpty() ? Paths.get(override).toAbsolutePath()
                    : jarDir.toFile().canWrite() && (!Files.exists(besideJar) || besideJar.toFile().canWrite())
                    ? besideJar : Paths.get(System.getProperty("user.home"), ".mod-agent", "mod-agent.log");
        } catch (Exception e) {
            System.err.println("[Mod-Agent] Cannot select log file; console logging remains available: " + e);
        }
        LOG_FILE = selected;
        // Only reset our own default log.
        if (LOG_FILE != null && override.isEmpty()) {
            try { Files.deleteIfExists(LOG_FILE); }
            catch (IOException failure) {
                System.err.println("[Mod-Agent] Cannot reset log file; will attempt append: " + failure);
            }
        }
    }

    public static synchronized void log(String message) {
        final String line = String.format(
                "[%s] [%s] [Mod-Agent] %s",
                LocalTime.now().format(TIME_FORMAT),
                Thread.currentThread().getName(),
                message
        );

        try {
            System.out.println(line);
            System.out.flush();
        } catch (Throwable ignored) {}

        // File
        if (LOG_FILE == null) return;
        try {
            if (LOG_FILE.getParent() != null) Files.createDirectories(LOG_FILE.getParent());

            try (BufferedWriter writer = Files.newBufferedWriter(
                    LOG_FILE,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE
            )) {
                writer.write(line);
                writer.newLine();
                writer.flush();
            }
        } catch (IOException e) {
            try {
                System.err.println("[Mod-Agent] Failed to write log file: " + e);
                System.err.flush();
            } catch (Throwable ignored) {}
        }
    }

    public static synchronized void log(String message, Throwable t) {
        log(message);
        log(stackTraceToString(t));
    }

    private static String stackTraceToString(Throwable t) {
        final StringWriter stringWriter = new StringWriter();
        final PrintWriter printWriter = new PrintWriter(stringWriter);
        t.printStackTrace(printWriter);
        printWriter.flush();
        return stringWriter.toString();
    }
}
