package com.ronanplugins.bettersync.editor.advancedmodreq;

import com.ronanplugins.bettersync.editor.EditorFiles;
import com.ronanplugins.bettersync.editor.EditorProfile;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

/** Fixed settings retain their schema; supported collections allow typed named entries. */
public final class EditorConfig {
    public static final int MAX_BYTES = 128 * 1024;
    private static final Pattern TOKENS = Pattern.compile("(^|[\\s\\[\\]{},])[!&*]", Pattern.MULTILINE);
    private EditorConfig() { }

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
        for (String section : Arrays.asList("Settings", "Notifications", "Economy", "Alias", "Category", "Template"))
            if (!parsed.isConfigurationSection(section)) throw new IOException("Missing AdvancedModreq section: " + section);
        if (!parsed.isList("DefaultListFilters")) throw new IOException("Missing DefaultListFilters list.");
        return parsed;
    }

    public static void validate(String original, String edited) throws Exception {
        YamlConfiguration before = parse(original), after = parse(edited);
        if (!Objects.equals(before.get("Language-File"), after.get("Language-File")))
            throw new IOException("Change Language-File locally, not through the web editor.");
        compare(before, after, "config", 0);

    }

    private static Object normalize(Object value) {
        return value instanceof ConfigurationSection ? ((ConfigurationSection) value).getValues(false) : value;
    }

    private static void compare(Object oldValue, Object newValue, String path, int depth) throws IOException {
        if (depth > 32) throw new IOException("Configuration nesting is too deep.");
        oldValue = normalize(oldValue); newValue = normalize(newValue);
        if (path.equals("config.Category.Categories") || path.equals("config.Template.Templates")) {
            Map<String, Object> old = entries(oldValue, path), next = entries(newValue, path);
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("Importance", 0); schema.put("Item", "bedrock");
            if (path.endsWith("Categories")) schema.put("Description", Collections.emptyList());
            else {
                schema.put("Message", ""); schema.put("Name", "");
                schema.put("Lore", Collections.emptyList()); schema.put("Category", "");
            }
            for (Map.Entry<String, Object> entry : next.entrySet()) {
                Object value = normalize(entry.getValue()), previous = normalize(old.get(entry.getKey()));
                if (!(value instanceof Map)) throw new IOException("Expected named settings: " + path);
                Map<?, ?> fields = (Map<?, ?>) value;
                Map<?, ?> prior = previous instanceof Map ? (Map<?, ?>) previous : Collections.emptyMap();
                for (Map.Entry<?, ?> field : fields.entrySet()) {
                    Object key = field.getKey(), item = field.getValue();
                    String at = path + "." + entry.getKey() + "." + key;
                    if ("Description".equals(key) && item instanceof String) continue;
                    if (schema.containsKey(key)) compare(schema.get(key), item, at, depth + 1);
                    else if (prior.containsKey(key)) compare(prior.get(key), item, at, depth + 1);
                    else throw new IOException("Unknown setting: " + at);
                    if ("Importance".equals(key) && (((Number) item).intValue() < 0 || ((Number) item).intValue() > 5))
                        throw new IOException("Importance must be 0–5.");
                    if ("Item".equals(key) && !Objects.equals(prior.get(key), item)) validateMaterial((String) item);
                }
                for (Object key : prior.keySet()) if (!schema.containsKey(key) && !fields.containsKey(key))
                    throw new IOException("Keep existing setting: " + key);
            }
            return;
        }
        if (path.equals("config.DefaultListFilters")) {
            Set<String> seen = new HashSet<>();
            for (Map.Entry<String, Object> entry : entries(newValue, path).entrySet()) {
                String key = entry.getKey().toUpperCase(Locale.ROOT); Object value = entry.getValue();
                if (!seen.add(key)) throw new IOException("Duplicate filter: " + key);
                if (Arrays.asList("RESOLVED", "FLAGGED", "CLAIMED", "OPENED", "CLOSED").contains(key)) {
                    if (!(value instanceof Boolean)) throw new IOException("Expected boolean filter.");
                } else if (Arrays.asList("RATING", "IMPORTANCE").contains(key)) {
                    if (!(value instanceof Number) || ((Number) value).doubleValue() != Math.rint(((Number) value).doubleValue()) || ((Number) value).doubleValue() < 0 || ((Number) value).doubleValue() > 5)
                        throw new IOException("Filter must be 0–5.");
                } else if (key.equals("CATEGORY")) {
                    if (!(value instanceof String)) throw new IOException("Expected category text.");
                } else throw new IOException("Unknown filter: " + key);
            }
            return;
        }
        if (path.equals("config.Alias.List")) {
            stringList(newValue, path);
            for (Object alias : (List<?>) newValue) if (!((String) alias).matches("[A-Za-z0-9_-]{1,100}")) throw new IOException("Invalid command alias.");
            return;
        }
        if (oldValue instanceof Map) {
            if (!(newValue instanceof Map)) throw new IOException("Expected section: " + path);
            Map<?, ?> oldMap = (Map<?, ?>) oldValue, newMap = (Map<?, ?>) newValue;
            if (!oldMap.keySet().equals(newMap.keySet())) throw new IOException("Keep existing setting names: " + path);
            for (Object key : oldMap.keySet()) compare(oldMap.get(key), newMap.get(key), path + "." + key, depth + 1);
        } else if (oldValue instanceof List) {
            if (!(newValue instanceof List)) throw new IOException("Expected list: " + path);
            List<?> oldList = (List<?>) oldValue, newList = (List<?>) newValue;
            if (newList.size() > 1000) throw new IOException("List too long: " + path);
            boolean structured = oldList.stream().anyMatch(v -> normalize(v) instanceof Map);
            if (structured && oldList.size() != newList.size()) throw new IOException("Keep existing world/group entries: " + path);
            for (int i = 0; i < newList.size(); i++) {
                Object baseline = structured ? oldList.get(i) : oldList.isEmpty() ? "" : oldList.get(0);
                compare(baseline, newList.get(i), path + "[" + i + "]", depth + 1);
            }
        } else if (oldValue instanceof Number) {
            if (!(newValue instanceof Number)) throw new IOException("Expected number: " + path);
            double n = ((Number) newValue).doubleValue();
            if (!Double.isFinite(n) || n < Integer.MIN_VALUE || n > Integer.MAX_VALUE ||
                    ((oldValue instanceof Integer || oldValue instanceof Long) && n != Math.rint(n)))
                throw new IOException("Invalid number: " + path);
            if (Arrays.asList("config.Settings.MinimumWords", "config.Settings.MessageCutOff.Max", "config.Settings.Message-Delay", "config.Settings.Cooldown.Time", "config.Economy.Price").contains(path) && n < 0)
                throw new IOException("Setting must be non-negative.");
        } else if (oldValue instanceof Boolean) {
            if (!(newValue instanceof Boolean)) throw new IOException("Expected boolean: " + path);
        } else if (oldValue instanceof String) {
            if (!(newValue instanceof String)) throw new IOException("Expected text: " + path);
            if (path.startsWith("config.Notifications.") && path.endsWith(".Sound") && !Objects.equals(oldValue, newValue) && !"null".equalsIgnoreCase((String) newValue))
                org.bukkit.Sound.valueOf(((String) newValue).toUpperCase(Locale.ROOT));
        } else if (!Objects.equals(oldValue, newValue)) throw new IOException("Unsupported setting: " + path);
    }

    private static void validateMaterial(String item) throws IOException {
        String[] parts = item.split(":", -1);
        try {
            org.bukkit.Material.valueOf(parts[0].toUpperCase(Locale.ROOT));
            if (parts.length > 2 || (parts.length == 2 && (Integer.parseInt(parts[1]) < 0 || Integer.parseInt(parts[1]) > 32767)))
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) { throw new IOException("Invalid item material or data value."); }
    }

    private static Map<String, Object> entries(Object value, String path) throws IOException {
        if (!(value instanceof List) || ((List<?>) value).size() > 1000) throw new IOException("Expected named list: " + path);
        Map<String, Object> result = new LinkedHashMap<>();
        for (Object item : (List<?>) value) {
            item = normalize(item);
            if (!(item instanceof Map) || ((Map<?, ?>) item).isEmpty()) throw new IOException("Expected named entry: " + path);
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) item).entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (key.trim().isEmpty() || !key.equals(key.trim()) || key.length() > 100 || Pattern.compile("[\\x00-\\x1f]").matcher(key).find() || result.containsKey(key))
                    throw new IOException("Invalid or duplicate entry name.");
                result.put(key, entry.getValue());
            }
        }
        return result;
    }

    private static void stringList(Object value, String path) throws IOException {
        if (!(value instanceof List) || ((List<?>) value).size() > 1000) throw new IOException("Expected text list: " + path);
        for (Object item : (List<?>) value)
            if (!(item instanceof String) || ((String) item).trim().isEmpty()) throw new IOException("Expected non-empty text: " + path);
    }

    public static Path apply(Path file, byte[] snapshot, String edited, EditorFiles.Reload reload) throws Exception {
        return EditorFiles.apply(EditorProfile.ADVANCEDMODREQ, file, snapshot, edited, reload);
    }

    public static boolean equivalent(String expected, String actual) throws Exception {
        return tree(parse(expected)).equals(tree(parse(actual)));
    }

    private static Object tree(Object value) {
        value = normalize(value);
        if (value instanceof Map) {
            Map<Object, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) result.put(entry.getKey(), tree(entry.getValue()));
            return result;
        }
        if (value instanceof List) {
            List<Object> result = new ArrayList<>();
            for (Object item : (List<?>) value) result.add(tree(item));
            return result;
        }
        return value;
    }

    public static byte[] read(Path file) throws IOException { return EditorFiles.read(file); }
    public static void replace(Path file, byte[] bytes) throws IOException { EditorFiles.replace(file, bytes); }
}
