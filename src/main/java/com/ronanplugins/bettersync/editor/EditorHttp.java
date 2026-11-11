package com.ronanplugins.bettersync.editor;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Protocol transport; contains no Bukkit or scheduler calls. */
final class EditorHttp {
    private final String website;
    private final String editorPlugin;
    EditorHttp(String website, String editorPlugin) {
        this.website = EditorClient.validateWebsite(website);
        this.editorPlugin = editorPlugin;
    }
    JsonObject request(String method, String path, JsonObject body, String syncToken) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(website + path).openConnection();
        connection.setConnectTimeout(5000); connection.setReadTimeout(10000); connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod(method); connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("X-Editor-Actor", "plugin");
        connection.setRequestProperty("User-Agent", "BetterSync-Editor/4");
        connection.setRequestProperty("X-Editor-Protocol", "4");
        connection.setRequestProperty("X-Editor-Plugin", editorPlugin);
        if (syncToken != null) {
            connection.setRequestProperty("X-Editor-Sync-Token", syncToken);
        }
        try {
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true); connection.setFixedLengthStreamingMode(bytes.length);
                connection.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("Relay HTTP " + status);
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[8192]; int count;
                while ((count = in.read(chunk)) != -1) {
                    if (buffer.size() + count > EditorFiles.MAX_BYTES * 6 + 4096) throw new IOException("Relay response too large.");
                    buffer.write(chunk, 0, count);
                }
                return new JsonParser().parse(new String(buffer.toByteArray(), StandardCharsets.UTF_8)).getAsJsonObject();
            }
        } finally { connection.disconnect(); }
    }
}
