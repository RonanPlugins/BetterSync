package com.ronanplugins.bettersync.editor;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class EditorHttpTest {
    private HttpServer server;
    private String website;
    @Before public void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        website = "http://127.0.0.1:" + server.getAddress().getPort();
    }
    @After public void stop() { server.stop(0); }

    @Test public void preservesProtocolAndPluginIdentityForAnyPlugin() throws Exception {
        AtomicReference<String> identity = new AtomicReference<>();
        AtomicReference<String> schemaVersion = new AtomicReference<>();
        AtomicReference<String> protocol = new AtomicReference<>();
        AtomicReference<String> actor = new AtomicReference<>();
        AtomicReference<String> token = new AtomicReference<>();
        AtomicReference<String> request = new AtomicReference<>();
        server.createContext("/api/editor/sync", exchange -> {
            schemaVersion.set(exchange.getRequestHeaders().getFirst("X-Editor-Schema-Version"));
            identity.set(exchange.getRequestHeaders().getFirst("X-Editor-Plugin"));
            protocol.set(exchange.getRequestHeaders().getFirst("X-Editor-Protocol"));
            actor.set(exchange.getRequestHeaders().getFirst("X-Editor-Actor"));
            token.set(exchange.getRequestHeaders().getFirst("X-Editor-Sync-Token"));
            request.set(exchange.getRequestURI().toString());
            byte[] bytes = "{\"content\":\"test\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        for (String id : new String[] {"example", "another-example"}) {
            JsonObject result = new EditorHttp(website, id, "1").request("GET", "/api/editor/sync", null, "Secret12");
            assertEquals("test", result.get("content").getAsString());
            assertEquals(id, identity.get());
            assertEquals("1", schemaVersion.get());
            assertEquals("4", protocol.get()); assertEquals("plugin", actor.get());
            assertEquals("Secret12", token.get()); assertEquals("/api/editor/sync", request.get());
        }
    }

    @Test public void doesNotFollowRedirectsOrExposeErrorBodies() throws Exception {
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", website + "/secret");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        try {
            new EditorHttp(website, "example", "2").request("GET", "/redirect", null, "Secret12");
            fail("Must reject redirects");
        } catch (IOException error) { assertEquals("Relay HTTP 302", error.getMessage()); }
    }

    @Test public void boundsResponseSize() throws Exception {
        server.createContext("/large", exchange -> {
            byte[] bytes = new byte[EditorFiles.MAX_BYTES * 6 + 4097];
            exchange.sendResponseHeaders(200, bytes.length);
            try { exchange.getResponseBody().write(bytes); } finally { exchange.close(); }
        });
        try {
            new EditorHttp(website, "example", "2").request("GET", "/large", null, null);
            fail("Must reject oversized response");
        } catch (IOException error) { assertEquals("Relay response too large.", error.getMessage()); }
    }

    @Test public void rejectsUnfilteredOrUnsafeWebsiteMetadata() {
        for (String url : new String[] {"${editor.website}", "file:///tmp/config", "https://user:secret@example.com", "https://example.com?token=secret", "https://example.com/#fragment"}) {
            try { EditorClient.validateWebsite(url); fail("Accepted " + url); }
            catch (IllegalArgumentException expected) { }
        }
        assertEquals("https://example.com", EditorClient.validateWebsite("https://example.com/"));
    }
}
