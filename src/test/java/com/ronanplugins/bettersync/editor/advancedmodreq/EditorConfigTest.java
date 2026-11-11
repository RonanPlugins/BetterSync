package com.ronanplugins.bettersync.editor.advancedmodreq;

import com.ronanplugins.bettersync.editor.advancedmodreq.EditorConfig;
import org.junit.Test;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class EditorConfigTest {
    private String config() throws Exception {
        return new String(Files.readAllBytes(Paths.get("src/test/resources/advancedmodreq.yml")), StandardCharsets.UTF_8);
    }
    @Test public void validatesDefaultsAndCollections() throws Exception {
        String original = config();
        EditorConfig.validate(original, original);
        EditorConfig.validate(original, original.replace("MinimumWords: 5", "MinimumWords: 7"));
        EditorConfig.validate(original, original.replace("- Opened: true", "- Importance: 3\n  - Category: Griefing"));
        EditorConfig.validate(original, original.replace("- Griefing:", "- Support:"));
        EditorConfig.validate(original, original.replace("Item: 'tnt'", "Item: 'paper'"));
        for (String invalid : new String[] {
            original.replace("MinimumWords: 5", "MinimumWords: -1"),
            original.replace("Price: 25", "Price: -1"),
            original.replace("Importance: 5", "Importance: 6"),
            original.replace("en.yml", "other.yml"),
            original.replace("Item: 'tnt'", "Item: 'NOT_A_MATERIAL'"),
            original.replace("Sound: 'BLOCK_ANVIL_USE'", "Sound: 'NOT_A_SOUND'"),
            original.replace("- Opened: true", "- Opened: true\n  - opened: false"),
            original.replace("- Opened: true", "- Unknown: true"),
            original.replace("- 'report'", "- '/bad alias'"),
            original + "\nUnsafe: !!java/object {}\n"
        }) {
            try { EditorConfig.validate(original, invalid); fail("Accepted invalid configuration"); }
            catch (Exception expected) { }
        }
    }
    @Test public void backupAndReloadFailure() throws Exception {
        String original = config(), edited = original.replace("MinimumWords: 5", "MinimumWords: 7");
        Path file = Files.createTempDirectory("modreq-editor-test").resolve("config.yml");
        Files.write(file, original.getBytes(StandardCharsets.UTF_8));
        Path backup = EditorConfig.apply(file, original.getBytes(StandardCharsets.UTF_8), edited, () -> {});
        assertEquals(original, new String(Files.readAllBytes(backup), StandardCharsets.UTF_8));
        assertTrue(EditorConfig.equivalent(edited, new String(Files.readAllBytes(file), StandardCharsets.UTF_8)));
        try {
            EditorConfig.apply(file, edited.getBytes(StandardCharsets.UTF_8), original, () -> { throw new Exception("reload failed"); });
            fail("Expected reload failure");
        } catch (com.ronanplugins.bettersync.editor.EditorFiles.ReloadFailure expected) { }
        assertEquals(original, new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    }
}
