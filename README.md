# BetterSync

Java 8 compatible web editor library for AdvancedModreq, BetterClaims, BetterRTP,
and BetterRTP Addons. Each plugin shades and relocates this library into its own
JAR. BetterSync is not a Bukkit plugin and is not installed on the server.

## Maven dependency from GitHub

The library is distributed from [RonanPlugins/BetterSync](https://github.com/RonanPlugins/BetterSync)
through [JitPack](https://docs.jitpack.io/). Consumers need no library checkout,
Maven reactor module, `systemPath`, or manual `mvn install`.

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>
<dependencies>
    <dependency>
        <groupId>com.github.RonanPlugins</groupId>
        <artifactId>BetterSync</artifactId>
        <version>1.1.0</version>
    </dependency>
</dependencies>
```

Include `com.github.RonanPlugins:BetterSync` in any Shade artifact allowlist and
relocate `com.ronanplugins.bettersync` to your plugin's private library package.
Bukkit, Bungee chat, and Gson are provided by the server, not bundled here.

**First release:** the consumer POMs target `1.1.0`. Push the library source and
create the Git tag `1.1.0` before merging the consumer changes. Ask JitPack to build
that tag and confirm it succeeds. Until then, this new dependency is unavailable
remotely. A public GitHub repository supports public consumption; private
repositories require JitPack access configuration.

For release verification, build a consumer from its own checkout with an empty
Maven cache (`mvn -Dmaven.repo.local=/tmp/consumer-release-check clean verify`).
This checks remote resolution; building this library locally alone does not.
Never reuse a release tag for changed code. Release a new version and update the
consumer `bettersync.version` properties instead.

## Integration

```java
editor = new EditorClient(
    this,
    EditorSchema.load(this, "META-INF/editor-schema.json"),
    EditorClient.website(this, "META-INF/advancedmodreq-editor.properties"),
    tasks::player,
    sender -> { reloadConfig(); reload(sender); },
    sender -> !(sender instanceof Player) || sender.hasPermission("advancedmodreq.editor"),
    () -> loadedConfiguration.saveToString()
);
```

The filtered properties resource contains `website=${editor.website}`. Each plugin
owns its endpoint metadata, including BetterRTP Addons. The client reads resources
through the owning plugin, so multiple shaded copies cannot select each other's
endpoint.

Commands call `editor.upload(sender)` and `editor.redeem(sender, token)`; plugin
shutdown calls `editor.close()` before shutting down the scheduler.

The player scheduler callback takes a UUID (null means console) and a recipient
callback. It must resolve that recipient on the appropriate server/player thread,
pass null if the player is unavailable, and avoid callbacks after plugin disable.
A `Consumer<Runnable>` overload supports existing server-thread schedulers. The
library's HTTP worker never invokes plugin reloads directly.

Profiles preserve each plugin's configuration validation and protocol 4 identity.
Common transport, bounded reads, backup creation, atomic replacement, and reload
verification live in the library. Saving creates a backup of the current file;
reload failure leaves the new file in place and reports the failure, as before.
No background polling or automatic sync is performed.

## Development

Run `./mvnw clean verify` with JDK 17. Output targets Java 8. The regression fixtures
are self-contained; no plugin source checkout is required to build this library.
`jitpack.yml` builds and tests the published artifact on JitPack's infrastructure.

## Plugin-owned validation schemas (1.1.0)

New integrations supply `EditorSchema` instead of `EditorProfile`. The old enum and
constructors remain for binary/source compatibility with 1.0.x; their validators are
legacy behavior and are not used by the four migrated plugins. BetterSync main sources
contain no copies of the new plugin schemas. Test fixtures are release snapshots.

Schema resources are owned by each plugin repository and published as public JSON assets
on this repository's GitHub release. Website builds download those assets by pinned URL
and SHA-256; they do not need plugin checkouts. The `x-bettersync` object declares `format`,
`plugin`, `schemaVersion`, and `displayName`. Schema version is independent of plugin and
library versions. Never change a published schema in place.

Format 1 supports these JSON Schema keywords: `type` (including a union), `properties`,
`required`, `additionalProperties`, `items`, `propertyNames`, `minProperties` on named
entry maps, `minimum`, `maximum`, `maxItems`, `pattern`, `enum`, `default`, `title`,
`description`, `$id`, and `$schema`. This is an explicitly limited dialect: `$ref`,
`oneOf`, and other unsupported keywords are rejected rather than silently ignored.
Defaults are UI suggestions; they do not rewrite the user's YAML.

BetterSync's edit policy adds:

- `x-named`: an editable YAML list of maps keyed by unique entry names.
- `x-editableProperties`: declared properties may be added/removed; existing unknown
  properties must be preserved. Other objects retain their baseline keys.
- `x-immutable`: values must remain equal to the local baseline.
- `x-rules`: path-pattern constraints shared by matching fields.
- `x-ranges`, `x-objectRanges`: ordered numeric field pairs; `x-inherit` merges a default
  section before checking ranges. `x-lessThan` bounds a number by another setting.
- `x-entryName`, `x-maxNumericName`, `x-caseInsensitive`, `x-caseInsensitiveKeys`: named
  collection and enum rules.
- `x-equivalentDefaults`: declared defaults are considered when checking reload equivalence.
- `x-defaultChecks`: check an omitted field's effective default (for example a menu slot).
- `x-serverCheck`: Java runtime checks (`material`, `sound`, `item`, `date`), optionally
  limited to changed values by `x-checkChanged`, with `x-allow` literals. The browser
  cannot claim that a value exists on a particular running Minecraft server.
- `x-editor.collection`: UI collection behavior.

Both evaluators preserve unknown baseline settings and enforce bounded input and nesting.
Custom YAML tags, aliases, object-serialization markers, and encoded escape sequences
remain prohibited. Sync is still validated against the receiving server's current file.

New clients send the schema identity on upload and `X-Editor-Schema-Version` on redeem.
The website must support that schema before the client is deployed. Missing/mismatched
response versions are rejected before a config write. Use the website migrations before
deploying the schema-aware plugins.
