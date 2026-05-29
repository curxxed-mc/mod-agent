package net.curxxed.dev.agent.config;

import net.curxxed.dev.agent.AgentLog;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class Config {

    public static List<ModEntry> parseConfig(String configPath) {
        final List<ModEntry> mods = new ArrayList<>();

        try {
            final String content = new String(Files.readAllBytes(new File(configPath).toPath()), StandardCharsets.UTF_8);
            final String[] blocks = content.split("\\{");

            for (int i = 1; i < blocks.length; i++) {
                final String block = blocks[i];
                if (!block.contains("jar")) continue;
                final String jar = extractValue(block, "jar");
                final String mixin = extractValue(block, "mixin");
                final String property = extractValue(block, "property");
                final String asm = extractValue(block, "asm");
                final String exclude = extractValue(block, "exclude");
                if (jar != null) mods.add(new ModEntry(jar, mixin, property, asm, exclude));
            }
        } catch (IOException e) {
            AgentLog.log("Failed to read config: " + e);
        }
        return mods;
    }

    private static String extractValue(String block, String key) {
        String search = "\"" + key + "\"";
        int keyIdx = block.indexOf(search);
        if (keyIdx == -1) return null;
        int colon = block.indexOf(":", keyIdx);
        if (colon == -1) return null;
        int start = block.indexOf("\"", colon + 1);
        if (start == -1) return null;
        int end = block.indexOf("\"", start + 1);
        if (end == -1) return null;
        return block.substring(start + 1, end);
    }
}
