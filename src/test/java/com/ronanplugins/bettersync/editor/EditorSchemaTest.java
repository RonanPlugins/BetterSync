package com.ronanplugins.bettersync.editor;
import org.junit.Test;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class EditorSchemaTest {
    private String read(String name) throws Exception { return new String(Files.readAllBytes(Paths.get("src/test/resources/"+name)),StandardCharsets.UTF_8); }
    private EditorSchema schema(String plugin) throws Exception { return new EditorSchema(read("schemas/"+plugin+"-1.json")); }
    private void rejected(EditorSchema schema,String before,String after) throws Exception {
        try { schema.validate(before,after); fail("Accepted invalid config"); } catch (java.io.IOException | IllegalArgumentException expected) { }
    }
    @Test public void validatesEachBundledConfigAndPreservesIdentity() throws Exception {
        for(String id:new String[]{"betterrtp","betterrtp-addons","betterclaims","advancedmodreq"}) {
            EditorSchema s=schema(id);String yaml=read(id+".yml").replace("golden_shovel","GOLD_SPADE");
            assertEquals(id,s.id());assertEquals("1",s.schemaVersion());s.validate(yaml,yaml);
            rejected(s,yaml,yaml+"\nInjected: true\n");rejected(s,yaml,yaml.replace("en.yml","other.yml"));
            rejected(s,yaml,yaml+"\nUnsafe: !!java/object {}\n");
        }
    }
    @Test public void validatesAllCollectionFixtures() throws Exception {
        EditorSchema s=schema("betterrtp");String before=read("collections/baseline.yml");
        try(java.util.stream.Stream<Path> files=Files.list(Paths.get("src/test/resources/collections"))) {
            for(Path file:(Iterable<Path>)files::iterator) {
                String edited=new String(Files.readAllBytes(file),StandardCharsets.UTF_8);
                if(file.getFileName().toString().startsWith("invalid-")) rejected(s,before,edited); else s.validate(before,edited);
            }
        }
    }
    @Test public void preservesModreqCollectionsAndRuntimeChecks() throws Exception {
        EditorSchema s=schema("advancedmodreq");String before=read("advancedmodreq.yml");
        s.validate(before,before.replace("- Griefing:","- Support:"));s.validate(before,before.replace("- Opened: true","- Importance: 3\n  - Category: Griefing"));
        for(String after:new String[]{before.replace("MinimumWords: 5","MinimumWords: -1"),before.replace("Importance: 5","Importance: 6"),before.replace("Item: 'tnt'","Item: 'NOT_A_MATERIAL'"),before.replace("- Opened: true","- Opened: true\n  - opened: false")})rejected(s,before,after);
    }
    @Test public void appliesSchemaAndEquivalentDefaults() throws Exception {
        EditorSchema s=schema("betterclaims");String before=read("betterclaims.yml").replace("golden_shovel","GOLD_SPADE"),after=before.replace("MaxClaims: 3","MaxClaims: 7");
        Path file=Files.createTempDirectory("schema-apply").resolve("config.yml");Files.write(file,before.getBytes(StandardCharsets.UTF_8));
        Path backup=EditorFiles.apply(s,file,before.getBytes(StandardCharsets.UTF_8),after,()->{});assertEquals(before,new String(Files.readAllBytes(backup),StandardCharsets.UTF_8));
        assertTrue(s.equivalent(after,after.replace("DefaultFlags: {}","DefaultFlags: {ALLOW_REQUESTS: true, PVP: false}")));
        rejected(s,before,before.replace("DefaultFlags: {}","DefaultFlags: {BAD: true}"));
        try { EditorFiles.apply(s,file,after.getBytes(StandardCharsets.UTF_8),before,()->{throw new Exception("reload");});fail(); }catch(EditorFiles.ReloadFailure expected){}
        assertEquals(before,new String(Files.readAllBytes(file),StandardCharsets.UTF_8));
    }
    @Test public void enforcesAddonMenuBoundsAndWarnings() throws Exception {
        EditorSchema s=schema("betterrtp-addons");String before=read("betterrtp-addons.yml");
        s.validate(before,before.replace("600: ","300: ").replace("world_nether:","survival:"));
        for(String after:new String[]{before.replace("Lines: 3","Lines: 7"),before.replace("Slot: 3","Slot: 27"),before.replace("600:","bad:"),before.replace("AutoRefresh: 0","AutoRefresh: -1")})rejected(s,before,after);
    }
    @Test public void preservesExplicitNullBaseline() throws Exception {
        String base="Settings: {}\nDefault: {}\n";
        rejected(schema("betterrtp"),base+"Future: null\n",base+"Future: injected\n");
    }
    @Test public void rejectsWrongPluginSchema() throws Exception { rejected(schema("advancedmodreq"),read("betterrtp.yml"),read("betterrtp.yml")); }
}
