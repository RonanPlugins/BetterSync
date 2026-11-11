package com.ronanplugins.bettersync.editor;

import com.google.gson.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Plugin-owned form description. Only declared field types are checked. */
public final class EditorSchema {
    private final JsonObject schema;
    private final String plugin, version, name;

    public EditorSchema(String json) {
        schema = new JsonParser().parse(json).getAsJsonObject();
        JsonObject meta = schema.getAsJsonObject("x-bettersync");
        if (meta == null || meta.get("format").getAsInt() != 1)
            throw new IllegalArgumentException("Unsupported editor schema format");
        plugin = meta.get("plugin").getAsString();
        version = meta.get("schemaVersion").getAsString();
        name = meta.get("displayName").getAsString();
        if (!plugin.matches("[a-z0-9-]{1,64}") || !version.matches("[A-Za-z0-9._-]{1,40}"))
            throw new IllegalArgumentException("Invalid schema identity");
    }
    public static EditorSchema load(JavaPlugin plugin, String resource) {
        try (InputStream in = plugin.getResource(resource)) {
            if (in == null) throw new IOException("Missing " + resource);
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] bytes = new byte[4096]; int count;
            while ((count = in.read(bytes)) != -1) {
                if (out.size() + count > 256 * 1024) throw new IOException("Schema too large");
                out.write(bytes, 0, count);
            }
            return new EditorSchema(new String(out.toByteArray(), StandardCharsets.UTF_8));
        } catch (IOException error) { throw new IllegalStateException("Cannot load plugin editor schema", error); }
    }
    public String id() { return plugin; }
    public String displayName() { return name; }
    public String schemaVersion() { return version; }
    private Object data(String text) throws Exception { return tree(EditorYaml.parse(text)); }
    public void parse(String text) throws Exception { check(schema, data(text), "config", 0); }
    public void validate(String before, String after) throws Exception { parse(after); }
    public boolean equivalent(String expected, String actual) throws Exception {
        Object a = data(expected), b = data(actual);
        defaults(schema, a); defaults(schema, b);
        return a.equals(b);
    }
    private static void check(JsonObject s, Object value, String path, int depth) throws IOException {
        if (depth > 32) throw new IOException("Configuration nesting is too deep");
        if (s.has("type")) {
            JsonArray types = new JsonArray();
            if (s.get("type").isJsonArray()) types = s.getAsJsonArray("type"); else types.add(s.get("type"));
            boolean matches = false;
            for (JsonElement type : types) matches |= type(type.getAsString(), value);
            if (!matches) throw new IOException("Invalid field type: " + path);
        }
        if (value instanceof Map) {
            if (s.has("required")) for (JsonElement key : s.getAsJsonArray("required"))
                if (!((Map<?, ?>) value).containsKey(key.getAsString())) throw new IOException("Missing section: " + key.getAsString());
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet())
                check(property(s, String.valueOf(entry.getKey())), entry.getValue(), path + "." + entry.getKey(), depth + 1);
        } else if (value instanceof List) {
            if (((List<?>) value).size() > 1000) throw new IOException("Too many list entries: " + path);
            for (Object item : (List<?>) value) check(object(s, "items"), item, path + "[]", depth + 1);
        }
    }
    private static boolean type(String type, Object value) {
        switch (type) {
            case "object": return value instanceof Map;
            case "array": return value instanceof List;
            case "string": return value instanceof String;
            case "boolean": return value instanceof Boolean;
            case "number": return value instanceof Number && Double.isFinite(((Number) value).doubleValue());
            case "integer": return value instanceof Number && Double.isFinite(((Number) value).doubleValue()) && ((Number) value).doubleValue() == Math.rint(((Number) value).doubleValue());
            case "null": return value == null;
            default: throw new IllegalArgumentException("Unknown field type: " + type);
        }
    }
    // Some plugins materialize defaults during reload. Compare their effective values.
    @SuppressWarnings("unchecked")
    private static void defaults(JsonObject s, Object value) {
        if (!(value instanceof Map)) return;
        Map<String, Object> values = (Map<String, Object>) value;
        for (Map.Entry<String, JsonElement> p : object(s, "properties").entrySet()) {
            JsonObject child = p.getValue().getAsJsonObject();
            if (s.has("x-equivalentDefaults") && s.get("x-equivalentDefaults").getAsBoolean() && !values.containsKey(p.getKey()) && child.has("default"))
                values.put(p.getKey(), new Gson().fromJson(child.get("default"), Object.class));
            defaults(child, values.get(p.getKey()));
        }
    }
    private static JsonObject object(JsonObject s, String key) {
        return s.has(key) && s.get(key).isJsonObject() ? s.getAsJsonObject(key) : new JsonObject();
    }
    private static JsonObject property(JsonObject s, String key) {
        JsonObject properties = object(s, "properties");
        return properties.has(key) ? properties.getAsJsonObject(key) : object(s, "additionalProperties");
    }
    private static Object tree(Object v) {
        if (v instanceof ConfigurationSection) v=((ConfigurationSection)v).getValues(false);
        if (v instanceof Map) { Map<String,Object> result=new LinkedHashMap<>(); for(Map.Entry<?,?> e:((Map<?,?>)v).entrySet()) result.put(String.valueOf(e.getKey()),tree(e.getValue())); return result; }
        if (v instanceof List) { List<Object> result=new ArrayList<>(); for(Object item:(List<?>)v) result.add(tree(item)); return result; }
        return v;
    }
}
