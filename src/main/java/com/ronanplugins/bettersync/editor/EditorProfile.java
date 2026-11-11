package com.ronanplugins.bettersync.editor;

/** Supported relay identities and their configuration validation rules. */
public enum EditorProfile {
    ADVANCEDMODREQ("advancedmodreq", "AdvancedModreq") {
        public void parse(String text) throws Exception { com.ronanplugins.bettersync.editor.advancedmodreq.EditorConfig.parse(text); }
        public void validate(String before, String after) throws Exception { com.ronanplugins.bettersync.editor.advancedmodreq.EditorConfig.validate(before, after); }
        public boolean equivalent(String expected, String actual) throws Exception { return com.ronanplugins.bettersync.editor.advancedmodreq.EditorConfig.equivalent(expected, actual); }
    },
    BETTERCLAIMS("betterclaims", "BetterClaims") {
        public void parse(String text) throws Exception { com.ronanplugins.bettersync.editor.betterclaims.EditorConfig.parse(text); }
        public void validate(String before, String after) throws Exception { com.ronanplugins.bettersync.editor.betterclaims.EditorConfig.validate(before, after); }
        public boolean equivalent(String expected, String actual) throws Exception { return com.ronanplugins.bettersync.editor.betterclaims.EditorConfig.equivalent(expected, actual); }
    },
    BETTERRTP("betterrtp", "BetterRTP") {
        public void parse(String text) throws Exception { com.ronanplugins.bettersync.editor.betterrtp.EditorConfig.parse(text); }
        public void validate(String before, String after) throws Exception { com.ronanplugins.bettersync.editor.betterrtp.EditorConfig.validate(before, after); }
        public boolean equivalent(String expected, String actual) throws Exception { return com.ronanplugins.bettersync.editor.betterrtp.EditorConfig.equivalent(expected, actual); }
    },
    BETTERRTP_ADDONS("betterrtp-addons", "BetterRTP Addons") {
        public void parse(String text) throws Exception { com.ronanplugins.bettersync.editor.betterrtp.EditorConfig.parse(text); }
        public void validate(String before, String after) throws Exception { com.ronanplugins.bettersync.editor.betterrtp.EditorConfig.validate(before, after); }
        public boolean equivalent(String expected, String actual) throws Exception { return com.ronanplugins.bettersync.editor.betterrtp.EditorConfig.equivalent(expected, actual); }
    };
    private final String id;
    private final String displayName;
    EditorProfile(String id, String displayName) { this.id = id; this.displayName = displayName; }
    public String id() { return id; }
    public String displayName() { return displayName; }
    public abstract void parse(String text) throws Exception;
    public abstract void validate(String before, String after) throws Exception;
    public abstract boolean equivalent(String expected, String actual) throws Exception;
}
