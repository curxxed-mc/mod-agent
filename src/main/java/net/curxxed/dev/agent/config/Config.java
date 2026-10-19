package net.curxxed.dev.agent.config;

import net.curxxed.dev.agent.AgentLog;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Config {

    public static List<ModEntry> parseConfig(String configPath) {
        final List<ModEntry> mods = new ArrayList<>();

        try {
            File config = new File(configPath).getAbsoluteFile();
            Object root = Json.parse(new String(Files.readAllBytes(config.toPath()), StandardCharsets.UTF_8));
            if (!(root instanceof Map)) throw new IllegalArgumentException("Config must be an object");
            Object entries = ((Map<?, ?>) root).get("mods");
            if (!(entries instanceof List)) throw new IllegalArgumentException("Config must contain a mods array");
            for (Object entry : (List<?>) entries) {
                if (!(entry instanceof Map)) throw new IllegalArgumentException("Each mod entry must be an object");
                Map<?, ?> block = (Map<?, ?>) entry;
                String jar = extractValue(block, "jar");
                if (jar == null || jar.isEmpty()) throw new IllegalArgumentException("Each mod needs a jar path");
                File path = new File(jar);
                if (!path.isAbsolute()) path = new File(config.getParentFile(), jar);
                mods.add(new ModEntry(path.toPath().normalize().toString(), extractValue(block, "mixin"),
                        extractValue(block, "property"), extractValue(block, "asm"),
                        extractValue(block, "exclude"), extractValue(block, "entrypoint"),
                        extractValue(block, "remapLoaders")));
            }
        } catch (IOException | IllegalArgumentException e) {
            AgentLog.log("Failed to read config: " + e);
            mods.clear();
        }
        return mods;
    }

    private static String extractValue(Map<?, ?> block, String key) {
        Object value = block.get(key);
        if (value != null && !(value instanceof String)) throw new IllegalArgumentException(key + " must be a string");
        return (String) value;
    }
}
