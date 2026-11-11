package com.ronanplugins.bettersync.editor;

/** Configuration contract supplied by the owning plugin. */
public interface EditorDefinition {
    String id();
    String displayName();
    default String schemaVersion() { return null; }
    void parse(String text) throws Exception;
    void validate(String before, String after) throws Exception;
    boolean equivalent(String expected, String actual) throws Exception;
}
