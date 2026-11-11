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
        <version>1.0.1</version>
    </dependency>
</dependencies>
```

Include `com.github.RonanPlugins:BetterSync` in any Shade artifact allowlist and
relocate `com.ronanplugins.bettersync` to your plugin's private library package.
Bukkit, Bungee chat, and Gson are provided by the server, not bundled here.

**First release:** the consumer POMs target `1.0.1`. Push the library source and
create the Git tag `1.0.1` before merging the consumer changes. Ask JitPack to build
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
    EditorProfile.ADVANCEDMODREQ,
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
