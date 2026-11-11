# BetterSync

A Java 8 library for uploading plugin YAML to a web editor and applying saved edits.
Shade and relocate it into your plugin. No separate server plugin or local library
checkout is required.

## Maven

```xml
<repositories>
  <repository><id>jitpack.io</id><url>https://jitpack.io</url></repository>
</repositories>
<dependencies>
  <dependency>
    <groupId>com.github.RonanPlugins</groupId>
    <artifactId>BetterSync</artifactId>
    <version>2.0.0</version>
  </dependency>
</dependencies>
```

Relocate `com.ronanplugins.bettersync` to your plugin's private package. Bukkit,
Bungee chat and Gson are provided by the server. Releases are downloaded through
JitPack from this repository; consumers do not run `mvn install` locally.

## Integration

```java
editor = new EditorClient(
    this,
    EditorSchema.load(this, "META-INF/editor-schema.json"),
    EditorClient.website(this, "META-INF/editor.properties"),
    tasks::player,
    sender -> reloadConfig(),
    sender -> sender.hasPermission("example.editor"),
    () -> getConfig().saveToString()
);
```

The properties resource contains the filtered `website=${editor.website}` value.
Commands call `editor.upload(sender)` and `editor.redeem(sender, token)`; shutdown
calls `editor.close()`. The scheduler receives a UUID (null for console) and a
callback, resolves the recipient on its server/player thread, and passes null
when the recipient is unavailable. A `Consumer<Runnable>` constructor also supports
ordinary server-thread schedulers.

## Plugin-owned form metadata

The plugin owns and packages `META-INF/editor-schema.json`. Its own GitHub release
publishes that file for the website. BetterSync stores no plugin schemas, validators,
or plugin fixtures, and does not host new schema releases.

```json
{
  "x-bettersync": { "format": 1, "plugin": "example", "schemaVersion": "2", "displayName": "Example" },
  "type": "object",
  "properties": {
    "Enabled": { "type": "boolean", "default": true },
    "Count": { "type": "integer", "default": 5 },
    "Names": { "type": "array", "items": { "type": "string" } }
  }
}
```

The generic reader checks declared types (`object`, `array`, `string`, `boolean`,
`number`, `integer`, `null`, or a union), nested `properties`, `items`, typed
`additionalProperties`, and required section names. Unknown settings are allowed.
This is form metadata, not a complete JSON Schema engine. Range, enum, runtime
material/sound and cross-field validation are not enforced by BetterSync.
`enum`, `default`, titles and editor annotations may guide the website's controls.
`x-equivalentDefaults` allows declared defaults materialized during plugin reload
to compare equal to omitted values.

Schema versions travel with upload and sync requests. A mismatched response is
rejected before writing. Increment the schema version when changing field metadata.
The library retains bounded YAML parsing, rejects object deserialization/tags/aliases,
backs up the current file, writes atomically, and verifies reload results. Reload
failure is reported without rolling back the written file.

2.0.0 removes `EditorProfile`, `EditorDefinition`, all plugin-specific validators,
and the deprecated overloads. There is no compatibility layer.

## Development

Run `./mvnw clean verify` with JDK 17. Output targets Java 8. Tests use synthetic
schemas; building BetterSync requires no consumer repository. Publish a new immutable
Git tag for each release and verify JitPack before updating consumer dependencies.
