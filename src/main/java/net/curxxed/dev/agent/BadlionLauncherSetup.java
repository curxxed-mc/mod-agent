package net.curxxed.dev.agent;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

/**
 * Installs a java launcher for Lunar's Badlion "Java path" setting. The only launcher setting it edits
 * is settings.badlion.javaPath.
 */
public final class BadlionLauncherSetup {
    private BadlionLauncherSetup() {}

    static boolean windows() {
        return System.getProperty("os.name", "").startsWith("Windows");
    }

    public static void main(File agent, File defaultConfig, String[] args) throws Exception {
        Path runtime = null, output = Paths.get(System.getProperty("user.home"), ".mod-agent", "badlion-runtime");
        Path log = null;
        File config = defaultConfig;
        List<String> options = new ArrayList<>();
        boolean restore = false, writeSettings = true;
        for (int index = 0; index < args.length; index++) {
            String option = args[index];
            if (option.equals("--restore")) { restore = true; continue; }
            if (option.equals("--no-settings")) { writeSettings = false; continue; }
            if (index + 1 == args.length) throw new IllegalArgumentException("Missing value for " + option);
            String value = args[++index];
            if (option.equals("--runtime")) runtime = Paths.get(value);
            else if (option.equals("--output")) output = Paths.get(value);
            else if (option.equals("--config")) config = new File(value);
            else if (option.equals("--vm-option")) options.add(value);
            else if (option.equals("--log")) log = Paths.get(value);
            else throw new IllegalArgumentException("Unknown setup option: " + option);
        }
        if (runtime == null && !restore) runtime = detectRuntime();
        if (log == null) log = output.resolve("launcher.log");
        if (restore) {
            String previous = restoreSettings(output);
            System.out.println("Restored Lunar's Badlion Java path to "
                    + (previous.isEmpty() ? "its default (empty)." : previous));
            System.out.println("You can now delete " + output.toAbsolutePath());
            return;
        }
        Path bin = install(runtime, output, agent, config, options, log);
        System.out.println("Badlion launcher installed for real Java runtime: " + runtime.toAbsolutePath());
        if (writeSettings) {
            Path backup = wireSettings(output, bin);
            System.out.println("Set Lunar's Badlion Java path to this launcher (backup: " + backup.getFileName() + ").");
            System.out.println();
            System.out.println("Next steps:");
            System.out.println("  1. Fully exit Lunar, including its tray process (it overwrites settings on exit).");
            System.out.println("  2. Launch Badlion. mod-agent.log should show 'JVM agent load order' with this agent last.");
            System.out.println("To undo: java -jar <agent>.jar setup-badlion --restore --output " + output.toAbsolutePath());
        } else {
            System.out.println();
            System.out.println("Set Lunar's Badlion Java path to: " + bin);
            System.out.println("Leave Badlion's JVM arguments empty; the launcher adds the agent itself.");
        }
    }

    /** Records the current Lunar Badlion Java path once, then points it at the installed launcher. */
    public static Path wireSettings(Path output, Path bin) throws IOException {
        Path sidecar = output.resolve("lunar-javapath.before");
        if (!Files.exists(sidecar)) {
            String previous = LunarSettings.currentJavaPath();
            if (previous != null) Files.write(sidecar, previous.getBytes(StandardCharsets.UTF_8));
            else Files.createFile(sidecar);
        }
        return LunarSettings.setJavaPath(bin.toString());
    }

    /** Restores the Lunar Badlion Java path saved by the first wireSettings for this output dir. */
    public static String restoreSettings(Path output) throws IOException {
        Path sidecar = output.resolve("lunar-javapath.before");
        String previous = Files.exists(sidecar)
                ? new String(Files.readAllBytes(sidecar), StandardCharsets.UTF_8) : "";
        LunarSettings.setJavaPath(previous);
        Files.deleteIfExists(sidecar);
        return previous;
    }

    static Path detectRuntime() throws IOException {
        String home = System.getProperty("user.home");
        List<Path> roots = new ArrayList<>();
        roots.add(Paths.get(home, ".cache", "Badlion Client", "Data"));
        if (System.getenv("APPDATA") != null) roots.add(Paths.get(System.getenv("APPDATA"), "Badlion Client", "Data"));
        if (System.getenv("LOCALAPPDATA") != null) roots.add(Paths.get(System.getenv("LOCALAPPDATA"), "Badlion Client", "Data"));
        roots.add(Paths.get(home, "Library", "Application Support", "Badlion Client", "Data"));
        String java = windows() ? "java.exe" : "java";
        List<Path> found = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(root, "jre*")) {
                for (Path jre : entries) {
                    for (Path bin : new Path[]{jre.resolve("bin"), jre.resolve("Contents/Home/bin")}) {
                        if (Files.isRegularFile(bin.resolve(java))) found.add(bin);
                    }
                }
            }
        }

        Path configured = configuredRuntime();
        if (configured != null) return configured;
        if (found.size() == 1) return found.get(0);

        // Badlion keeps every runtime it ever downloaded, take the newest update.
        Path newest = null;
        int newestUpdate = -1;
        for (Path bin : found) {
            int update = java8Update(bin);
            if (update > newestUpdate) {
                newest = bin;
                newestUpdate = update;
            }
        }

        if (newest != null) {
            return newest;
        } else {
            final String message = found.isEmpty() ? "Unable to find Badlion's Java runtime" : "Found no Badlion Java 8 runtime: " + found;
            throw new IllegalArgumentException(message + ". Pass --runtime <bin directory containing " + java + ">");
        }
    }

    /// Lunar's current Badlion Java path when it names a real runtime, or the one our launcher already wraps.
    private static Path configuredRuntime() {
        final String java = windows() ? "java.exe" : "java";
        try {
            final String value = LunarSettings.currentJavaPath();
            if (value == null || value.trim().isEmpty()) return null;

            Path bin = Paths.get(value.trim());
            if (Files.isRegularFile(bin)) bin = bin.getParent();

            final Path conf = bin.resolve("java.conf");
            if (Files.isRegularFile(conf)) {
                for (String line : Files.readAllLines(conf, StandardCharsets.UTF_8)) {
                    if (line.startsWith("runtime=")) bin = Paths.get(line.substring("runtime=".length()).trim());
                }
            }

            return Files.isRegularFile(bin.resolve(java)) && !Files.exists(bin.resolve("java.conf")) ? bin : null;
        } catch (IOException | RuntimeException unreadable) {
            return null; // Scan Badlion's own runtimes as fallback
        }
    }

    /// Update number of a Java 8 runtime for its release file. Example "1.8.0_202 gives 202" gives 202.
    /// @return -1 if not Java 8.
    private static int java8Update(Path bin) {
        try {
            for (String line : Files.readAllLines(bin.getParent().resolve("release"), StandardCharsets.UTF_8)) {
                if (!line.startsWith("JAVA_VERSION=\"1.8.0_")) continue;
                final String digits = line.substring("JAVA_VERSION=\"1.8.0_".length()).replaceAll("[^0-9].*", "");
                return digits.isEmpty() ? 0 : Integer.parseInt(digits);
            }
        } catch (IOException | RuntimeException ignored) {}
        return -1;
    }

    public static Path install(Path runtime, Path output, File agent, File config, List<String> options, Path log)
            throws IOException, InterruptedException {
        runtime = runtime.toAbsolutePath().normalize();
        output = output.toAbsolutePath().normalize();
        Path bin = output.resolve("bin");
        String java = windows() ? "java.exe" : "java";
        if (!Files.isRegularFile(runtime.resolve(java))) {
            throw new IllegalArgumentException("No " + java + " in " + runtime + "; pass the real Java bin directory.");
        }
        if (Files.exists(runtime.resolve("java.conf"))) {
            throw new IllegalArgumentException(runtime + " is already a Mod-Agent launcher; pass the real Java bin directory.");
        }
        if (bin.equals(runtime)) throw new IllegalArgumentException("--output must not contain the real runtime");
        if (!agent.isFile()) throw new IllegalArgumentException("Agent JAR not found: " + agent);
        if (config == null || !config.isFile()) throw new IllegalArgumentException("Pass --config <agent-mods.json>.");
        String agentPath = agent.getAbsolutePath();
        if (agentPath.indexOf('=') >= 0) throw new IllegalArgumentException("Agent JAR path cannot contain '='; move the JAR.");
        StringBuilder conf = new StringBuilder("# Generated by setup-badlion. Values are literal; do not add quotes.\n");
        line(conf, "runtime", runtime.toString());
        line(conf, "agent", agentPath);
        line(conf, "config", config.getAbsolutePath());
        for (String option : options) {
            if (!option.startsWith("-D") || option.length() < 3) {
                throw new IllegalArgumentException("--vm-option must be a system property (-D...): " + option);
            }
            line(conf, "vm_option", option);
        }
        if (log != null) line(conf, "log", log.toAbsolutePath().toString());

        Files.createDirectories(bin);
        restrict(output, "rwx------");
        restrict(bin, "rwx------");
        if (windows()) compileWindowsLaunchers(runtime, bin);
        else {
            Path script = bin.resolve("java");
            write(script, resource("/launcher/java.sh"));
            restrict(script, "rwx------");
        }
        Path confFile = bin.resolve("java.conf");
        write(confFile, conf.toString().getBytes(StandardCharsets.UTF_8));
        restrict(confFile, "rw-------");
        return bin;
    }

    private static void line(StringBuilder conf, String key, String value) {
        if (value.isEmpty() || !value.trim().equals(value) || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0
                || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Unsupported " + key + " value (empty, line break, or surrounding spaces): " + value);
        }
        conf.append(key).append('=').append(value).append('\n');
    }

    private static void compileWindowsLaunchers(Path runtime, Path bin) throws IOException, InterruptedException {
        Path compiler = findCsc();
        Path source = Files.createTempFile("modagent-launcher-", ".cs");
        try {
            Files.write(source, resource("/launcher/JavaLauncher.cs"));
            for (String[] target : new String[][]{{"exe", "java.exe"}, {"winexe", "javaw.exe"}}) {
                if (!Files.isRegularFile(runtime.resolve(target[1]))) continue;
                Path temporary = bin.resolve(target[1] + ".new");
                Process process = new ProcessBuilder(compiler.toString(), "/nologo", "/optimize+",
                        "/target:" + target[0], "/out:" + temporary, source.toString())
                        .redirectErrorStream(true).start();
                String messages = new String(read(process.getInputStream()), StandardCharsets.UTF_8);
                if (process.waitFor() != 0 || !Files.isRegularFile(temporary)) {
                    throw new IOException("C# compiler failed for " + target[1] + ":\n" + messages);
                }
                Files.move(temporary, bin.resolve(target[1]), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(source);
        }
    }

    static Path findCsc() {
        String windowsDirectory = System.getenv("WINDIR") != null ? System.getenv("WINDIR") : "C:\\Windows";
        for (String framework : new String[]{"Framework64", "Framework"}) {
            Path csc = Paths.get(windowsDirectory, "Microsoft.NET", framework, "v4.0.30319", "csc.exe");
            if (Files.isRegularFile(csc)) return csc;
        }
        throw new IllegalStateException("The .NET Framework 4 C# compiler (csc.exe) was not found under "
                + windowsDirectory + "\\Microsoft.NET. It ships with Windows 10/11; enable \".NET Framework 4.8\""
                + " in Windows Features if it was removed.");
    }

    private static void write(Path target, byte[] bytes) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".new");
        Files.write(temporary, bytes);
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void restrict(Path path, String permissions) throws IOException {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
        } catch (UnsupportedOperationException ignored) { /* Windows uses inherited ACLs. */ }
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream input = BadlionLauncherSetup.class.getResourceAsStream(name)) {
            if (input == null) throw new IOException("Agent JAR is missing " + name);
            return read(input);
        }
    }

    private static byte[] read(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int count; (count = input.read(buffer)) != -1;) output.write(buffer, 0, count);
        return output.toByteArray();
    }
}
