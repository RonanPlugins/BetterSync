package com.ronanplugins.bettersync.editor;

import com.google.gson.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/** BetterSync schema format 1: documented JSON Schema subset plus edit-policy annotations. */
public final class EditorSchema implements EditorDefinition {
    private final JsonObject schema;
    private final String plugin, version, name;
    private static final Object MISSING = new Object();
    private static final JsonObject EMPTY = new JsonObject();

    public EditorSchema(String json) {
        schema = new JsonParser().parse(json).getAsJsonObject();
        checkSchema(schema, 0);
        JsonObject meta = schema.getAsJsonObject("x-bettersync");
        if (meta == null || meta.get("format").getAsInt() != 1) throw new IllegalArgumentException("Unsupported editor schema format");
        plugin = meta.get("plugin").getAsString(); version = meta.get("schemaVersion").getAsString();
        name = meta.get("displayName").getAsString();
        if (!plugin.matches("[a-z0-9-]{1,64}") || !version.matches("[A-Za-z0-9._-]{1,40}"))
            throw new IllegalArgumentException("Invalid schema identity");
    }
    private static void checkSchema(JsonObject s, int depth) {
        if (depth > 32) throw new IllegalArgumentException("Schema nesting is too deep");
        Set<String> supported = new HashSet<>(Arrays.asList("$schema", "$id", "title", "description", "type", "required", "properties", "additionalProperties", "items", "propertyNames", "minProperties", "minimum", "maximum", "maxItems", "pattern", "enum", "default", "x-bettersync", "x-rules", "x-named", "x-caseInsensitiveKeys", "x-caseInsensitive", "x-immutable", "x-entryName", "x-maxNumericName", "x-editableProperties", "x-equivalentDefaults", "x-inherit", "x-ranges", "x-objectRanges", "x-lessThan", "x-defaultChecks", "x-editor", "x-serverCheck", "x-checkChanged", "x-allow"));
        for (Map.Entry<String,JsonElement> entry : s.entrySet())
            if (!supported.contains(entry.getKey())) throw new IllegalArgumentException("Unsupported schema keyword: " + entry.getKey());
        if (s.has("type")) {
            JsonArray types = new JsonArray();
            if (s.get("type").isJsonArray()) types = s.getAsJsonArray("type"); else types.add(s.get("type"));
            for (JsonElement type : types) if (!Arrays.asList("object","array","string","boolean","number","integer","null").contains(type.getAsString())) throw new IllegalArgumentException("Unsupported schema type");
        }
        for (JsonElement child : object(s,"properties").entrySet().stream().map(Map.Entry::getValue).toArray(JsonElement[]::new)) checkSchema(child.getAsJsonObject(), depth+1);
        for (String key : Arrays.asList("items","additionalProperties","propertyNames")) if(s.has(key) && s.get(key).isJsonObject()) checkSchema(s.getAsJsonObject(key),depth+1);
        for (JsonElement rule : array(s,"x-rules")) { Pattern.compile(rule.getAsJsonObject().get("path").getAsString()); checkSchema(rule.getAsJsonObject().getAsJsonObject("schema"),depth+1); }
        if(s.has("pattern")) Pattern.compile(s.get("pattern").getAsString());
        if(s.has("x-serverCheck") && !Arrays.asList("date","material","item","sound").contains(s.get("x-serverCheck").getAsString())) throw new IllegalArgumentException("Unsupported server check");
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
    public void parse(String text) throws Exception {
        Object data = data(text);
        shape(schema, data, "config");
        Map<String,Object> map = map(data);
        for (JsonElement key : array(schema, "required")) {
            String k = key.getAsString();
            if (!map.containsKey(k)) fail("Missing section: " + k);
            shape(property(schema, k), map.get(k), k);
        }
    }
    public void validate(String before, String after) throws Exception {
        parse(before); parse(after);
        Object original = data(before), edited = data(after);
        compare(schema, original, edited, "config", 0, edited);
    }
    public boolean equivalent(String expected, String actual) throws Exception {
        parse(expected); parse(actual);
        Object a = data(expected), b = data(actual);
        defaults(schema, a); defaults(schema, b);
        return a.equals(b);
    }
    private void defaults(JsonObject s, Object value) {
        if (!(value instanceof Map)) return;
        Map<String,Object> m = map(value);
        for (Map.Entry<String,JsonElement> p : object(s, "properties").entrySet()) {
            JsonObject child = p.getValue().getAsJsonObject();
            if (flag(s, "x-equivalentDefaults") && !m.containsKey(p.getKey()) && child.has("default"))
                m.put(p.getKey(), new Gson().fromJson(child.get("default"), Object.class));
            if (m.containsKey(p.getKey())) defaults(child, m.get(p.getKey()));
        }
    }
    private void compare(JsonObject s, Object old, Object value, String path, int depth, Object root) throws Exception {
        if (depth > 32) fail(path);
        shape(s, value, path);
        constraints(s, old, value, path, root);
        for (JsonElement rule : array(schema, "x-rules")) {
            JsonObject r = rule.getAsJsonObject();
            if (Pattern.matches(r.get("path").getAsString(), path)) constraints(r.getAsJsonObject("schema"), old, value, path, root);
        }
        if (flag(s, "x-named")) {
            Map<String,Object> previous = entries(old, s, path), next = entries(value, s, path);
            JsonObject item = object(s, "items");
            for (Map.Entry<String,Object> entry : next.entrySet()) {
                String key = entry.getKey();
                constraints(object(item, "propertyNames"), null, key, path, root);
                JsonObject child = property(item, key, flag(s, "x-caseInsensitiveKeys"));
                if (child == null) fail(path + "." + key);
                compare(child, previous.containsKey(key) ? previous.get(key) : MISSING, entry.getValue(), path + "." + key, depth + 1, root);
            }
            return;
        }
        if (value instanceof Map) {
            if (old != MISSING && !(old instanceof Map)) fail(path);
            Map<String,Object> prior = old instanceof Map ? map(old) : Collections.emptyMap(), next = map(value);
            boolean editable = flag(s, "x-editableProperties");
            if (!editable && old != MISSING && !prior.keySet().equals(next.keySet())) fail(path);
            for (JsonElement required : array(s, "required")) if (!next.containsKey(required.getAsString())) fail(path);
            for (String key : next.keySet()) {
                JsonObject child = property(s, key);
                boolean known = object(s, "properties").has(key);
                if (child == null || (editable && !known && !prior.containsKey(key))) fail(path + "." + key);
                compare(child, prior.containsKey(key) ? prior.get(key) : MISSING, next.get(key), path + "." + key, depth + 1, root);
            }
            if (editable) for (String key : prior.keySet())
                if (!object(s, "properties").has(key) && !next.containsKey(key)) fail(path);
            Map<String,Object> effective = new LinkedHashMap<>();
            if (s.has("x-inherit")) { Object inherited = at(root, s.get("x-inherit").getAsString()); if (inherited instanceof Map) effective.putAll(map(inherited)); }
            effective.putAll(next);
            ranges(array(schema, "x-objectRanges"), effective, path); ranges(array(s, "x-ranges"), effective, path);
            for (Map.Entry<String,JsonElement> p : object(s, "x-defaultChecks").entrySet())
                if (!next.containsKey(p.getKey())) constraints(property(s, p.getKey()), null, new Gson().fromJson(p.getValue(), Object.class), path + "." + p.getKey(), root);
        } else if (value instanceof List) {
            if (old != MISSING && !(old instanceof List)) fail(path);
            List<?> previous = old instanceof List ? (List<?>) old : Collections.emptyList(), next = (List<?>) value;
            if (next.size() > 1000) fail(path);
            boolean structured = previous.stream().anyMatch(v -> v instanceof Map);
            if (!s.has("items") && structured && previous.size() != next.size()) fail(path);
            for (int i=0; i<next.size(); i++) {
                Object baseline = s.has("items") ? (i < previous.size() ? previous.get(i) : MISSING) : structured ? previous.get(i) : previous.isEmpty() ? "" : previous.get(0);
                compare(object(s, "items"), baseline, next.get(i), path + "[" + i + "]", depth + 1, root);
            }
        } else if (old != MISSING && !s.has("type")) {
            if (old instanceof Number) {
                if (!(value instanceof Number) || (whole(old) && !whole(value))) fail(path);
            } else if (old == null ? value != null : value == null || !old.getClass().equals(value.getClass())) fail(path);
        }
    }
    private void shape(JsonObject s, Object value, String path) throws IOException {
        if (!s.has("type")) return;
        JsonElement type = s.get("type");
        if (type.isJsonArray()) { for (JsonElement t : type.getAsJsonArray()) if (type(t.getAsString(), value)) return; fail(path); }
        else if (!type(type.getAsString(), value)) fail(path);
    }
    private boolean type(String type, Object v) {
        switch(type) { case "object": return v instanceof Map; case "array": return v instanceof List; case "string": return v instanceof String;
            case "boolean": return v instanceof Boolean; case "number": return v instanceof Number; case "integer": return v instanceof Number && whole(v); case "null": return v == null; default: return false; }
    }
    private void constraints(JsonObject s, Object old, Object v, String path, Object root) throws Exception {
        if (flag(s, "x-immutable") && !Objects.equals(old, v)) fail("Change locally: " + path);
        if (v instanceof Map && s.has("minProperties") && ((Map<?,?>)v).size() < s.get("minProperties").getAsInt()) fail(path);
        if (v instanceof Number) {
            double n = ((Number)v).doubleValue();
            if (!Double.isFinite(n) || n < Integer.MIN_VALUE || n > Integer.MAX_VALUE || (s.has("minimum") && n < s.get("minimum").getAsDouble()) || (s.has("maximum") && n > s.get("maximum").getAsDouble())) fail(path);
            if (s.has("x-lessThan")) {
                JsonObject r = s.getAsJsonObject("x-lessThan"); Object maximum = at(root, r.get("path").getAsString());
                if (!(maximum instanceof Number) || n >= ((Number)maximum).doubleValue() * r.get("multiply").getAsDouble()) fail(path);
            }
        }
        if (v instanceof String) {
            String text=(String)v;
            if (s.has("pattern") && !Pattern.compile(s.get("pattern").getAsString()).matcher(text).find()) fail(path);
            if (flag(s,"x-entryName") && !validName(text)) fail(path);
            if (s.has("x-maxNumericName")) { try { if (Long.parseLong(text) > s.get("x-maxNumericName").getAsLong()) fail(path); } catch (NumberFormatException e) { fail(path); } }
        }
        if (s.has("enum")) {
            boolean found=false;
            for (JsonElement option : s.getAsJsonArray("enum")) if (flag(s,"x-caseInsensitive") ? option.getAsString().equalsIgnoreCase(String.valueOf(v)) : option.equals(new Gson().toJsonTree(v))) found=true;
            if (!found) fail(path);
        }
        if (v instanceof List && s.has("maxItems") && ((List<?>)v).size() > s.get("maxItems").getAsInt()) fail(path);
        if (s.has("x-serverCheck") && (!flag(s,"x-checkChanged") || !Objects.equals(old,v))) {
            for (JsonElement allowed : array(s,"x-allow")) if (allowed.getAsString().equalsIgnoreCase(String.valueOf(v))) return;
            String text=String.valueOf(v);
            switch(s.get("x-serverCheck").getAsString()) {
                case "date": new java.text.SimpleDateFormat(text); break;
                case "material": if (org.bukkit.Material.matchMaterial(text.toUpperCase(Locale.ROOT)) == null) fail(path); break;
                case "sound": org.bukkit.Sound.valueOf(text.toUpperCase(Locale.ROOT)); break;
                case "item":
                    String[] parts=text.split(":",-1); org.bukkit.Material.valueOf(parts[0].toUpperCase(Locale.ROOT));
                    if (parts.length > 2 || (parts.length == 2 && (Integer.parseInt(parts[1]) < 0 || Integer.parseInt(parts[1]) > 32767))) fail(path);
                    break;
                default: fail("Unknown server check");
            }
        }
    }
    private static void ranges(JsonArray rules, Map<String,Object> value, String path) throws IOException {
        for (JsonElement rule : rules) {
            JsonObject r=rule.getAsJsonObject(); Object a=value.get(r.get("min").getAsString()), b=value.get(r.get("max").getAsString());
            if (a instanceof Number && b instanceof Number && (flag(r,"strict") ? ((Number)a).doubleValue() >= ((Number)b).doubleValue() : ((Number)a).doubleValue() > ((Number)b).doubleValue())) fail(path);
        }
    }
    private static Map<String,Object> entries(Object value, JsonObject s, String path) throws IOException {
        if (value == MISSING) return Collections.emptyMap();
        if (!(value instanceof List) || ((List<?>)value).size()>1000) { fail(path); }
        Map<String,Object> result=new LinkedHashMap<>();
        for (Object item : (List<?>)value) {
            if (!(item instanceof Map) || map(item).isEmpty()) fail(path);
            for (Map.Entry<String,Object> e : map(item).entrySet()) {
                String key=flag(s,"x-caseInsensitiveKeys") ? e.getKey().toLowerCase(Locale.ROOT) : e.getKey();
                if (!validName(key) || result.containsKey(key)) fail(path);
                result.put(key,e.getValue());
            }
        }
        return result;
    }
    private static boolean validName(String s) { return !s.trim().isEmpty() && s.equals(s.trim()) && s.length()<=100 && !Pattern.compile("[\\x00-\\x1f]").matcher(s).find(); }
    private static JsonObject property(JsonObject s,String key) { return property(s,key,false); }
    private static JsonObject property(JsonObject s,String key,boolean insensitive) {
        for (Map.Entry<String,JsonElement> p:object(s,"properties").entrySet()) if (insensitive ? p.getKey().equalsIgnoreCase(key) : p.getKey().equals(key)) return p.getValue().getAsJsonObject();
        JsonElement extra=s.get("additionalProperties");
        return extra==null || (extra.isJsonPrimitive() && extra.getAsBoolean()) ? EMPTY : extra.isJsonObject() ? extra.getAsJsonObject() : null;
    }
    private static Object at(Object root,String path) { Object v=root; for(String key:path.split("\\.")) { if (!(v instanceof Map)) return null; v=map(v).get(key); } return v; }
    private static boolean whole(Object n) { return n instanceof Number && ((Number)n).doubleValue()==Math.rint(((Number)n).doubleValue()); }
    private static boolean flag(JsonObject s,String key) { return s.has(key) && s.get(key).getAsBoolean(); }
    private static JsonObject object(JsonObject s,String key) { return s.has(key) && s.get(key).isJsonObject() ? s.getAsJsonObject(key) : EMPTY; }
    private static JsonArray array(JsonObject s,String key) { return s.has(key) ? s.getAsJsonArray(key) : new JsonArray(); }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value) { return (Map<String,Object>)value; }
    private static Object tree(Object v) {
        if (v instanceof ConfigurationSection) v=((ConfigurationSection)v).getValues(false);
        if (v instanceof Map) { Map<String,Object> result=new LinkedHashMap<>(); for(Map.Entry<?,?> e:((Map<?,?>)v).entrySet()) result.put(String.valueOf(e.getKey()),tree(e.getValue())); return result; }
        if (v instanceof List) { List<Object> result=new ArrayList<>(); for(Object item:(List<?>)v) result.add(tree(item)); return result; }
        return v;
    }
    private static void fail(String path) throws IOException { throw new IOException("Invalid setting or type: " + path); }
}
