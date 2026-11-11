package com.ronanplugins.bettersync.editor;
import org.bukkit.configuration.file.YamlConfiguration;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.regex.Pattern;
final class EditorYaml {
    private static final int MAX_BYTES = EditorFiles.MAX_BYTES;
    private static final Pattern TOKENS = Pattern.compile("(^|[\\s\\[\\]{},])[!&*]", Pattern.MULTILINE);
    private EditorYaml() { }

    private static String withoutQuotedScalars(String text) {
        // Only complete, single-line quoted scalars: Minecraft color codes are text, not YAML anchors.
        return text.replaceAll("\"(?:\\\\[^\\r\\n]|[^\"\\\\\\r\\n])*\"|'(?:''|[^'\\r\\n])*'", "quoted");
    }

    public static YamlConfiguration parse(String text) throws Exception {
        // Bukkit supports object deserialization through the reserved == key. Reject equals
        // and encoded character escapes before invoking its parser, not only after parsing.
        if (text.indexOf("==") >= 0 || Pattern.compile("\\\\[xuU]").matcher(text).find())
            throw new IOException("Object serialization markers and encoded character escapes are not supported.");
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES || TOKENS.matcher(withoutQuotedScalars(text)).find())
            throw new IOException("Configuration too large, or YAML tags/anchors/aliases present.");
        int lines = 0, flow = 0;
        for (String line : text.split("\\n")) {
            if (++lines > 4000 || line.matches("^ {65}.*")) throw new IOException("Configuration is too complex.");
            for (char c : line.toCharArray()) if (c == '[' || c == '{') flow++;
        }
        if (flow > 128) throw new IOException("Too many flow collections.");
        YamlConfiguration parsed = new YamlConfiguration();
        parsed.loadFromString(text);
        return parsed;
    }

}
