package dev.curxxed.agent.lunar;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import dev.curxxed.agent.config.Json;

/**
 * Builds the -javaagent line for Lunar's JVM Arguments field. Lunar splits that field on every
 * space with no quoting, so when the agent JAR or config path contains a space, both are copied
 * to a folder without spaces first. Mod paths inside the config may keep spaces.
 */
public final class LunarJvmArgument {
    private LunarJvmArgument() {}

    public static final class Result {
        public final String argument;
        public final Path copiedTo;
        Result(String argument, Path copiedTo) { this.argument = argument; this.copiedTo = copiedTo; }
    }

    public static Path defaultFolder() {
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            String drive = System.getenv("SystemDrive");
            return Paths.get((drive == null || drive.isEmpty() ? "C:" : drive) + "\\", "mod-agent");
        }
        return Paths.get(System.getProperty("user.home"), ".mod-agent", "lunar");
    }

    public static Result prepare(File agent, File config, Path folder) throws IOException {
        if (!agent.isFile()) throw new IOException("Agent JAR not found: " + agent);
        if (config == null || !config.isFile()) throw new IOException("Mod configuration not found: " + config);
        Path agentPath = agent.toPath().toAbsolutePath().normalize();
        Path configPath = config.toPath().toAbsolutePath().normalize();
        if (!hasSpace(agentPath) && !hasSpace(configPath)) {
            return new Result(argument(agentPath, configPath), null);
        }

        folder = folder.toAbsolutePath().normalize();
        if (hasSpace(folder)) {
            throw new IOException("The copy folder also contains a space: " + folder + ". Move the agent to a folder without spaces.");
        }

        Files.createDirectories(folder);
        final Path agentCopy = folder.resolve("mod-agent.jar");
        if (!Files.exists(agentCopy) || !Files.isSameFile(agentPath, agentCopy)) {
            Files.copy(agentPath, agentCopy, StandardCopyOption.REPLACE_EXISTING);
        }

        Path configCopy = folder.resolve("agent-mods.json");
        String rewritten = absoluteModPaths(new String(Files.readAllBytes(configPath), StandardCharsets.UTF_8),
                configPath.getParent());
        if (!Files.exists(configCopy) || !Files.isSameFile(configPath, configCopy)) {
            Files.deleteIfExists(configCopy);
            Files.write(configCopy, rewritten.getBytes(StandardCharsets.UTF_8));
        }
        return new Result(argument(agentCopy, configCopy), folder);
    }

    private static boolean hasSpace(Path path) {
        return path.toString().indexOf(' ') >= 0;
    }

    private static String argument(Path agent, Path config) throws IOException {
        if (agent.toString().indexOf('=') >= 0) throw new IOException("The agent JAR path cannot contain '=': " + agent);
        return "-javaagent:" + agent + "=" + config;
    }

    /** Relative "jar" entries resolve beside the config, so make them absolute before moving it. */
    static String absoluteModPaths(String json, Path configDirectory) {
        Object root = Json.parse(json);
        Object mods = root instanceof Map ? ((Map<?, ?>) root).get("mods") : null;
        if (!(mods instanceof List)) throw new IllegalArgumentException("Config must contain a mods array");
        for (Object mod : (List<?>) mods) {
            if (!(mod instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> entry = (Map<String, Object>) mod;
            Object jar = entry.get("jar");
            if (jar instanceof String && !new File((String) jar).isAbsolute()) {
                entry.put("jar", configDirectory.resolve((String) jar).normalize().toString());
            }
        }
        StringBuilder out = new StringBuilder();
        write(out, root, 0);
        return out.append('\n').toString();
    }

    private static void write(StringBuilder out, Object value, int depth) {
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            if (map.isEmpty()) { out.append("{}"); return; }
            out.append("{\n");
            int i = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                indent(out, depth + 1);
                string(out, String.valueOf(entry.getKey()));
                out.append(": ");
                write(out, entry.getValue(), depth + 1);
                out.append(++i < map.size() ? ",\n" : "\n");
            }
            indent(out, depth);
            out.append('}');
        } else if (value instanceof List) {
            List<?> list = (List<?>) value;
            if (list.isEmpty()) { out.append("[]"); return; }
            out.append("[\n");
            for (int i = 0; i < list.size(); i++) {
                indent(out, depth + 1);
                write(out, list.get(i), depth + 1);
                out.append(i + 1 < list.size() ? ",\n" : "\n");
            }
            indent(out, depth);
            out.append(']');
        } else if (value instanceof String) {
            string(out, (String) value);
        } else if (value instanceof Double) {
            double number = (Double) value;
            out.append(number == Math.rint(number) && !Double.isInfinite(number) ? Long.toString((long) number) : Double.toString(number));
        } else {
            out.append(value);
        }
    }

    private static void indent(StringBuilder out, int depth) {
        for (int i = 0; i < depth; i++) out.append("  ");
    }

    private static void string(StringBuilder out, String value) {
        out.append('"').append(LunarSettings.escape(value)).append('"');
    }
}
