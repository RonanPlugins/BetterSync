package com.ronanplugins.bettersync.editor;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonPrimitive;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Independent config uploads; HTTP runs on a dedicated worker. No editor lifecycle. */
public final class EditorClient implements AutoCloseable {
    private final String website;
    private final EditorHttp http;
    private final EditorSchema profile;
    private final JavaPlugin plugin;
    private final String editorPlugin;
    private final String editorName;
    private final BiConsumer<UUID, Consumer<CommandSender>> sync;
    private final Consumer<CommandSender> reload;
    private final Predicate<CommandSender> allowed;
    private final Supplier<String> loadedConfig;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "BetterSync-editor"); thread.setDaemon(true); return thread;
    });
    private final java.util.concurrent.atomic.AtomicBoolean applying = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean stopped;

    public EditorClient(JavaPlugin plugin, EditorSchema profile, String website,
                        BiConsumer<UUID, Consumer<CommandSender>> sync, Consumer<CommandSender> reload,
                        Predicate<CommandSender> allowed, Supplier<String> loadedConfig) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.website = validateWebsite(website);
        this.editorPlugin = profile.id();
        this.http = new EditorHttp(this.website, this.editorPlugin, profile.schemaVersion());
        this.editorName = profile.displayName();
        this.sync = Objects.requireNonNull(sync, "sync");
        this.reload = Objects.requireNonNull(reload, "reload");
        this.allowed = Objects.requireNonNull(allowed, "allowed");
        this.loadedConfig = Objects.requireNonNull(loadedConfig, "loadedConfig");
    }

    /** Adapter for plugins whose scheduler runs callbacks on the server thread. */
    public EditorClient(JavaPlugin plugin, EditorSchema profile, String website,
                        Consumer<Runnable> sync, Consumer<CommandSender> reload,
                        Predicate<CommandSender> allowed, Supplier<String> loadedConfig) {
        this(plugin, profile, website, (id, callback) -> sync.accept(() ->
                callback.accept(id == null ? Bukkit.getConsoleSender() : Bukkit.getPlayer(id))),
                reload, allowed, loadedConfig);
    }

    /** Reads filtered build metadata from the owning plugin, never another plugin's resources. */
    public static String website(JavaPlugin plugin, String resource) {
        Properties properties = new Properties();
        try (InputStream input = plugin.getResource(resource)) {
            if (input == null) throw new IllegalStateException("Missing editor build metadata: " + resource);
            properties.load(input);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read editor build metadata", error);
        }
        return validateWebsite(properties.getProperty("website"));
    }

    static String validateWebsite(String value) {
        try {
            URI uri = new URI(Objects.requireNonNull(value, "website"));
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) ||
                    uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
                throw new IllegalArgumentException("Editor website must be an HTTP(S) URL without credentials, query or fragment");
            return value.replaceAll("/+$", "");
        } catch (URISyntaxException error) { throw new IllegalArgumentException("Invalid editor website", error); }
    }
    public synchronized void close() { stopped = true; worker.shutdownNow(); }

    public synchronized void upload(CommandSender sender) {
        if (stopped || !allowed.test(sender)) return;
        try {
            byte[] bytes = EditorFiles.read(configPath());
            String content = new String(bytes, StandardCharsets.UTF_8); profile.parse(content);
            Upload upload = new Upload(sender instanceof Player ? ((Player) sender).getUniqueId() : null);
            // Snapshot loaded worlds on the command thread, before starting HTTP work.
            List<String> worlds = new ArrayList<>();
            for (org.bukkit.World world : Bukkit.getWorlds()) worlds.add(world.getName());
            sender.sendMessage("§7Uploading a private " + editorName + " editor config…");
            worker.execute(() -> {
                try {
                    JsonObject body = new JsonObject(); body.addProperty("protocol", 4);
                    body.addProperty("playerId", upload.player == null ? null : upload.player.toString());
                    body.addProperty("configFile", "config.yml");
                    body.addProperty("plugin", editorPlugin);
                    body.addProperty("schemaVersion", profile.schemaVersion());
                    JsonArray availableWorlds = new JsonArray();
                    for (String world : worlds) availableWorlds.add(new JsonPrimitive(world));
                    body.add("worlds", availableWorlds);
                    body.addProperty("version", plugin.getDescription().getVersion()); body.addProperty("content", content);
                    JsonObject result = http.request("POST", "/api/editor/tokens", body, null);
                    String browserToken = result.get("token").getAsString();
                    if (!browserToken.matches("[A-Za-z0-9_-]{8}")) throw new IOException("Invalid relay response.");
                    if (stopped) return;
                    sync.accept(upload.player, recipient -> {
                        if (stopped) return;
                        if (recipient == null || !allowed.test(recipient)) return;
                        String url = website + "/editor/" + editorPlugin + "?token=" + browserToken;
                        recipient.sendMessage("§ePrivate editor link: expires in 24 hours. Save online, then run the sync command here. Do not share it.");
                        if (recipient instanceof Player) {
                            TextComponent link = new TextComponent("§a[Open " + editorName + " editor]");
                            link.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url));
                            ((Player) recipient).spigot().sendMessage(link);
                        } else recipient.sendMessage(url);
                    });
                } catch (Exception error) {
                    plugin.getLogger().warning("Editor upload failed (" + error.getClass().getSimpleName() + "). Check website/database availability.");
                    message(upload, "Could not upload the editor config. " + safeError(error));
                }
            });
        } catch (Exception error) { sender.sendMessage("§cEditor: " + error.getMessage()); }
    }

    private Path configPath() { return plugin.getDataFolder().toPath().resolve("config.yml"); }
    private void message(Upload upload, String text) {
        if (!stopped) sync.accept(upload.player, sender -> {
            if (!stopped && sender != null) sender.sendMessage("§eEditor: " + text);
        });
    }
    private void message(UUID playerId, String text) {
        if (!stopped) sync.accept(playerId, player -> {
            if (!stopped && player != null) player.sendMessage("§eEditor: " + text);
        });
    }
    public synchronized void redeem(CommandSender sender, String token) {
        if (stopped || !allowed.test(sender)) return;
        if (!token.matches("[A-Za-z0-9_-]{8}")) { sender.sendMessage("§cInvalid sync token. Copy the command from the editor."); return; }
        if (!applying.compareAndSet(false, true)) { sender.sendMessage("§eAn editor sync is already in progress."); return; }
        final UUID executor = sender instanceof Player ? ((Player) sender).getUniqueId() : null;
        sender.sendMessage("§7Downloading your saved configuration…");
        worker.execute(() -> {
            try {
                JsonObject response = http.request("GET", "/api/editor/sync", null, token);
                if (!"config.yml".equals(response.get("configFile").getAsString())) throw new IOException("Invalid sync target.");
                if (!response.has("schemaVersion") ||
                        !profile.schemaVersion().equals(response.get("schemaVersion").getAsString()))
                    throw new IOException("Editor schema version does not match this plugin.");
                String edited = response.get("content").getAsString();
                long expires = response.get("expiresAt").getAsLong();
                // Public commands can come from any editor; validate against this server, not a browser baseline.
                String original = new String(EditorFiles.read(configPath()), StandardCharsets.UTF_8);
                profile.validate(original, edited);
                sync.accept(executor, recipient -> apply(original, edited, expires, executor, recipient));
            } catch (Exception error) {
                applying.set(false);
                message(executor, "Sync failed: " + safeError(error) + " Save online again for a new command if this token was consumed.");
            }
        });
    }

    private static String safeError(Exception error) {
        // Do not log URLs, headers, raw responses, tokens, or config contents.
        if (error instanceof IOException && error.getMessage() != null && error.getMessage().startsWith("Relay HTTP "))
            return error.getMessage();
        return "Request or validation failed (" + error.getClass().getSimpleName() + ").";
    }

    private void apply(String original, String edited, long tokenExpires, UUID executor, CommandSender sender) {
        try {
            if (stopped) return;
            if (System.currentTimeMillis() >= tokenExpires) throw new IOException("Sync token expired before apply.");
            if (sender == null || !allowed.test(sender)) throw new IOException("The syncing player must be online and still have editor permission.");
            Path backup = EditorFiles.apply(profile, configPath(), original.getBytes(StandardCharsets.UTF_8), edited, () -> {
                reload.accept(sender);
                String loaded = loadedConfig.get();
                String persisted = new String(EditorFiles.read(configPath()), StandardCharsets.UTF_8);
                if (!profile.equivalent(persisted, loaded))
                    throw new IOException("Loaded configuration does not match the saved settings.");
            });
            sender.sendMessage("§aEditor changes applied and reloaded. Backup: " + backup.getFileName());
        } catch (EditorFiles.ReloadFailure error) {
            message(executor, "Configuration saved, but reload failed. The new file remains in place. Check the console and reload the plugin.");
            plugin.getLogger().warning("Editor config was written but reload failed; no rollback was performed.");
        } catch (Exception error) {
            message(executor, "Sync not applied: " + error.getMessage());
        } finally { applying.set(false); }
    }

    private static final class Upload {
        final UUID player;
        Upload(UUID player) { this.player = player; }
    }
}
