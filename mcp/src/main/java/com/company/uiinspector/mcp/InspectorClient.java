package com.company.uiinspector.mcp;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Thin HTTP client for the existing plugin inspector server (/api/*).
 * The MCP server reuses all live device discovery + capture capabilities.
 */
public final class InspectorClient {
    private final String baseUrl;
    private final String token;
    private final HttpClient http;

    public InspectorClient(String baseUrl, String token) {
        this.baseUrl = trimSlash(baseUrl == null ? "http://127.0.0.1:0" : baseUrl);
        this.token = token == null ? "" : token;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public String session() throws IOException, InterruptedException {
        return get("/api/session");
    }

    public String devices() throws IOException, InterruptedException {
        return get("/api/devices");
    }

    public String processes(String serial) throws IOException, InterruptedException {
        return get("/api/processes?serial=" + enc(serial));
    }

    public String catalog(String serial, String scope) throws IOException, InterruptedException {
        return get("/api/catalog?serial=" + enc(serial) + "&scope=" + enc(scope));
    }

    public String capture(String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/capture"))
                .timeout(Duration.ofSeconds(140))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return send(request);
    }

    private String get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(30)).GET().build();
        return send(request);
    }

    private URI uri(String path) {
        String sep = path.contains("?") ? "&" : "?";
        return URI.create(baseUrl + path + sep + "token=" + URLEncoder.encode(token, StandardCharsets.UTF_8));
    }

    private String send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 400) {
            throw new IOException("Inspector API HTTP " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }

    private static String trimSlash(String value) {
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    public static HttpRequest buildRequest(URI uri, HttpClient client, String method, String body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(140));
        if ("POST".equalsIgnoreCase(method)) {
            b.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body, StandardCharsets.UTF_8));
        } else {
            b.GET();
        }
        return b.build();
    }
}
