package com.ronanplugins.bettersync.editor;
import org.junit.Test;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class EditorSchemaTest {
    private final EditorSchema schema = new EditorSchema("{\"x-bettersync\":{\"format\":1,\"plugin\":\"example\",\"schemaVersion\":\"2\",\"displayName\":\"Example\"},\"type\":\"object\",\"properties\":{\"Count\":{\"type\":\"integer\"},\"Names\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}}}");
    @Test public void checksFieldTypesAndAllowsNewSettings() throws Exception {
        assertEquals("example",schema.id()); assertEquals("2",schema.schemaVersion());
        schema.validate("Count: 1", "Count: -2\nNames: [one, two]\nNew: true");
        try { schema.parse("Count: text"); fail(); } catch (java.io.IOException expected) { }
        try { schema.parse("Names: [1]"); fail(); } catch (java.io.IOException expected) { }
    }
    @Test public void rejectsUnsafeYamlBeforeParsing() throws Exception {
        for (String text : new String[] {"Unsafe: !!java/object {}", "Unsafe: {==: Something}", "A: &ref [one]\nB: *ref"}) {
            try { schema.parse(text); fail(); } catch (java.io.IOException expected) { }
        }
    }
    @Test public void backsUpAndReportsReloadFailure() throws Exception {
        Path file = Files.createTempDirectory("editor-test").resolve("config.yml");
        byte[] before = "Count: 1".getBytes(StandardCharsets.UTF_8); Files.write(file,before);
        Path backup = EditorFiles.apply(schema,file,before,"Count: 2",()->{});
        assertArrayEquals(before,Files.readAllBytes(backup));
        assertTrue(schema.equivalent("Count: 2", "# comment\nCount: 2"));
        try { EditorFiles.apply(schema,file,before,"Count: 3",()->{throw new Exception("reload");}); fail(); }
        catch (EditorFiles.ReloadFailure expected) { }
        assertEquals("Count: 3",new String(Files.readAllBytes(file),StandardCharsets.UTF_8));
    }
}
