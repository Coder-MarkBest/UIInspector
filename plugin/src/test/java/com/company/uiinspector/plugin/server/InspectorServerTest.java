package com.company.uiinspector.plugin.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InspectorServerTest {
    @Test
    void bindsLoopbackRequiresTokenAndServesTypedAssets() throws Exception {
        var api = new FakeApi();
        try (var server = new InspectorServer(
                "secret-token", api, path -> Map.of(
                        "index.html", "<main>Inspector</main>".getBytes(StandardCharsets.UTF_8),
                        "styles.css", "body{}".getBytes(StandardCharsets.UTF_8)).get(path))) {
            server.start();
            assertEquals("127.0.0.1", server.baseUri().getHost());
            var client = HttpClient.newHttpClient();

            var unauthorized = client.send(
                    HttpRequest.newBuilder(server.baseUri().resolve("api/session")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, unauthorized.statusCode());

            var page = client.send(HttpRequest.newBuilder(
                            server.baseUri().resolve("?token=secret-token")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, page.statusCode());
            assertEquals("text/html; charset=utf-8", page.headers().firstValue("content-type").orElseThrow());

            var css = client.send(HttpRequest.newBuilder(
                            server.baseUri().resolve("styles.css")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, css.statusCode());
            assertEquals("text/css; charset=utf-8", css.headers().firstValue("content-type").orElseThrow());
        }
    }

    @Test
    void rejectsCrossOriginMutationAndStopsAcceptingRequests() throws Exception {
        var api = new FakeApi();
        var server = new InspectorServer("token", api, path -> new byte[0]);
        server.start();
        URI capture = server.baseUri().resolve("api/capture?token=token");
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

        var forbidden = client.send(HttpRequest.newBuilder(capture)
                        .header("Origin", "http://malicious.example")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, forbidden.statusCode());
        assertEquals(0, api.captureCalls);

        URI mutate = server.baseUri().resolve("api/mutate?token=token");
        var ok = client.send(HttpRequest.newBuilder(mutate)
                        .header("Origin", server.baseUri().getScheme() + "://" + server.baseUri().getAuthority())
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, ok.statusCode());
        assertEquals(1, api.mutateCalls);

        assertEquals(405, client.send(HttpRequest.newBuilder(mutate).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());

        server.close();
        assertThrows(Exception.class, () -> client.send(
                HttpRequest.newBuilder(capture).GET().build(), HttpResponse.BodyHandlers.ofString()));
    }

    private static final class FakeApi implements InspectorServer.Api {
        int captureCalls;
        int mutateCalls;
        @Override public String session() { return "{\"status\":\"ready\"}"; }
        @Override public String devices() { return "[]"; }
        @Override public String processes(Map<String, String> query) { return "[]"; }
        @Override public String capture(String body, Map<String, String> query) {
            captureCalls++;
            return "{\"status\":\"captured\"}";
        }
        @Override public String mutate(String body, Map<String, String> query) {
            mutateCalls++;
            return "{\"status\":\"mutated\"}";
        }
        @Override public String highlight(String body, Map<String, String> query) {
            return "{\"ok\":true}";
        }
    }
}
