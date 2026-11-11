package com.ronanplugins.bettersync.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;

/** Bounded reads, backups and atomic writes shared by every editor profile. */
public final class EditorFiles {
    public static final int MAX_BYTES = 128 * 1024;
    private EditorFiles() { }
    @FunctionalInterface
    public interface Reload { void run() throws Exception; }

    @Deprecated
    public static Path apply(EditorProfile profile, Path file, byte[] snapshot, String edited, Reload reload) throws Exception {
        return apply((EditorDefinition) profile, file, snapshot, edited, reload);
    }

    public static Path apply(EditorDefinition profile, Path file, byte[] snapshot, String edited, Reload reload) throws Exception {
        profile.validate(new String(snapshot, StandardCharsets.UTF_8), edited);
        Path backups = file.getParent().resolve("editor-backups");
        Files.createDirectories(backups);
        Path backup = backups.resolve("config-" + System.currentTimeMillis() + "-" + UUID.randomUUID() + ".yml");
        // Back up the current file, but intentionally allow intervening local changes.
        Files.write(backup, read(file), StandardOpenOption.CREATE_NEW);
        replace(file, edited.getBytes(StandardCharsets.UTF_8));
        try {
            reload.run();
            if (!profile.equivalent(edited, new String(read(file), StandardCharsets.UTF_8)))
                throw new IOException("Saved configuration does not match the submission.");
        } catch (Exception failure) {
            throw new ReloadFailure(failure);
        }
        return backup;
    }

    public static final class ReloadFailure extends IOException {
        public ReloadFailure(Exception cause) { super("Configuration written, but reload failed. No rollback was performed.", cause); }
    }

    public static byte[] read(Path file) throws IOException {
        if (Files.size(file) > MAX_BYTES) throw new IOException("config.yml exceeds 128 KiB.");
        byte[] result = Files.readAllBytes(file);
        if (result.length > MAX_BYTES) throw new IOException("config.yml exceeds 128 KiB.");
        return result;
    }

    public static void replace(Path file, byte[] bytes) throws IOException {
        Path temp = Files.createTempFile(file.getParent(), ".editor-", ".tmp");
        try {
            Files.write(temp, bytes);
            // Fail closed if this filesystem cannot atomically replace the configuration.
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
}
