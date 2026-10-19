package net.curxxed.dev.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import net.curxxed.dev.agent.config.Json;

/**
 * Points Lunar's Badlion "Java path" at our launcher by replacing only that one string in
 * launcher.json. Everything else in the file is left byte-for-byte, and a timestamped backup
 * is written first. Lunar must be fully closed or it overwrites the file on exit.
 */
public final class LunarSettings {
    private LunarSettings() {}

    public static Path settingsFile() {
        String override = System.getProperty("modagent.lunar.settings", "");
        if (!override.isEmpty()) return Paths.get(override);
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isEmpty()) {
            Path windows = Paths.get(appData, ".lunarclient", "settings", "launcher.json");
            if (Files.exists(windows)) return windows;
        }
        return Paths.get(System.getProperty("user.home"), ".lunarclient", "settings", "launcher.json");
    }

    /** Current Badlion Java path, or null if the file or the setting is absent. */
    public static String currentJavaPath() throws IOException {
        Path file = settingsFile();
        if (!Files.isRegularFile(file)) return null;
        Object root = Json.parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        Object badlion = child(child(root, "settings"), "badlion");
        if (!(badlion instanceof Map)) return null;
        Object value = ((Map<?, ?>) badlion).get("javaPath");
        return value instanceof String ? (String) value : null;
    }

    private static Object child(Object parent, String key) {
        return parent instanceof Map ? ((Map<?, ?>) parent).get(key) : null;
    }

    /** Writes a backup, then replaces only the badlion.javaPath string. Returns the backup path. */
    public static Path setJavaPath(String newValue) throws IOException {
        Path file = settingsFile();
        if (!Files.isRegularFile(file)) {
            throw new IOException("Lunar settings not found at " + file + ". Launch Lunar once, then retry.");
        }
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        // Validate it is well-formed and actually has the badlion object before editing bytes.
        Object badlion = child(child(Json.parse(text), "settings"), "badlion");
        if (!(badlion instanceof Map)) {
            throw new IOException("Lunar settings has no Badlion section yet; open Lunar's Badlion settings once, then retry.");
        }
        String edited = replaceJavaPath(text, newValue);
        // Re-parse the result so a broken edit never reaches disk.
        if (!(child(child(Json.parse(edited), "settings"), "badlion") instanceof Map)) {
            throw new IOException("Internal error: edited settings failed validation");
        }
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(ZonedDateTime.now(ZoneOffset.UTC));
        Path backup = file.resolveSibling(file.getFileName() + ".backup-before-mod-agent-" + stamp);
        for (int suffix = 2; Files.exists(backup); suffix++) {
            backup = file.resolveSibling(file.getFileName() + ".backup-before-mod-agent-" + stamp + "-" + suffix);
        }
        Files.copy(file, backup, StandardCopyOption.COPY_ATTRIBUTES);
        Path temporary = file.resolveSibling(file.getFileName() + ".new");
        Files.write(temporary, edited.getBytes(StandardCharsets.UTF_8));
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        return backup;
    }

    static int badlionObjectStart(String text) {
        int key = text.indexOf("\"badlion\"");
        if (key < 0) throw new IllegalArgumentException("No badlion section");
        int brace = text.indexOf('{', key);
        if (brace < 0) throw new IllegalArgumentException("Malformed badlion section");
        return brace;
    }

    static int objectEnd(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') { i = skipString(text, i); continue; }
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        throw new IllegalArgumentException("Unbalanced braces");
    }

    /** Given the index of an opening quote, returns the index of the closing quote. */
    static int skipString(String text, int open) {
        for (int i = open + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') { i++; continue; }
            if (c == '"') return i;
        }
        throw new IllegalArgumentException("Unterminated string");
    }

    static String replaceJavaPath(String text, String newValue) {
        int start = badlionObjectStart(text);
        int end = objectEnd(text, start);
        int key = indexOfKey(text, "\"javaPath\"", start + 1, end);
        String literal = '"' + escape(newValue) + '"';
        if (key < 0) {
            // Insert right after the opening brace, preserving the file's indentation style.
            String indent = detectIndent(text, start);
            return text.substring(0, start + 1) + "\n" + indent + "\"javaPath\": " + literal + ","
                    + text.substring(start + 1);
        }
        int colon = text.indexOf(':', key);
        int valueQuote = text.indexOf('"', colon + 1);
        if (valueQuote < 0 || valueQuote > end) throw new IllegalArgumentException("javaPath value is not a string");
        int close = skipString(text, valueQuote);
        return text.substring(0, valueQuote) + literal + text.substring(close + 1);
    }

    /** Finds a key only at the top level of the object span (not inside nested strings/objects). */
    private static int indexOfKey(String text, String key, int from, int end) {
        int depth = 0;
        for (int i = from; i < end; i++) {
            char c = text.charAt(i);
            if (c == '"') {
                int close = skipString(text, i);
                // Only a key: a string at the object's top level immediately followed by ':'.
                if (depth == 0 && text.startsWith(key, i) && close == i + key.length() - 1) {
                    int after = close + 1;
                    while (after < end && " \t\r\n".indexOf(text.charAt(after)) >= 0) after++;
                    if (after < end && text.charAt(after) == ':') return i;
                }
                i = close;
                continue;
            }
            if (c == '{' || c == '[') depth++;
            else if (c == '}' || c == ']') depth--;
        }
        return -1;
    }

    private static String detectIndent(String text, int brace) {
        int lineStart = text.lastIndexOf('\n', brace) + 1;
        StringBuilder indent = new StringBuilder();
        for (int i = lineStart; i < brace && (text.charAt(i) == ' ' || text.charAt(i) == '\t'); i++) {
            indent.append(text.charAt(i));
        }
        indent.append(indent.length() > 0 && indent.charAt(0) == '\t' ? "\t" : "  ");
        return indent.toString();
    }

    static String escape(String value) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': result.append("\\\""); break;
                case '\\': result.append("\\\\"); break;
                case '\b': result.append("\\b"); break;
                case '\f': result.append("\\f"); break;
                case '\n': result.append("\\n"); break;
                case '\r': result.append("\\r"); break;
                case '\t': result.append("\\t"); break;
                default:
                    if (c < 32) result.append(String.format("\\u%04x", (int) c));
                    else result.append(c);
            }
        }
        return result.toString();
    }
}
