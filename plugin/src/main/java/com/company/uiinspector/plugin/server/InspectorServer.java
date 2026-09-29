package com.company.uiinspector.plugin.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class InspectorServer implements AutoCloseable {
    private final String token;
    private final Api api;
    private final ResourceLoader resources;
    private HttpServer server;
    private ExecutorService executor;
    private URI baseUri;

    public InspectorServer(String token, Api api, ResourceLoader resources) {
        if (token == null || token.length() < 5) throw new IllegalArgumentException("Session token is too short.");
        this.token = token;
        this.api = api;
        this.resources = resources;
    }

    public synchronized void start() throws IOException {
        if (server != null) return;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "uiinspector-http");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    public synchronized URI baseUri() {
        if (baseUri == null) throw new IllegalStateException("Server has not started.");
        return baseUri;
    }

    public URI browserUri() {
        return baseUri().resolve("?token=" + token);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            addSecurityHeaders(exchange);
            Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
            String path = exchange.getRequestURI().getPath();
            if (requiresToken(path) && !token.equals(query.get("token"))) {
                send(exchange, 401, "text/plain; charset=utf-8", "Session token required.");
                return;
            }
            if (path.startsWith("/api/")) {
                handleApi(exchange, path, query);
            } else {
                serveResource(exchange, path);
            }
        } catch (RuntimeException error) {
            if (exchange.getResponseCode() < 0) {
                send(exchange, 500, "application/json; charset=utf-8",
                        "{\"error\":\"Internal inspector error\"}");
            }
        }
    }

    private static boolean requiresToken(String path) {
        return "/".equals(path) || "/index.html".equals(path) || path.startsWith("/api/");
    }

    private void handleApi(HttpExchange exchange, String path, Map<String, String> query) throws IOException {
        if ("POST".equals(exchange.getRequestMethod()) && !sameOrigin(exchange)) {
            send(exchange, 403, "application/json; charset=utf-8", "{\"error\":\"Origin rejected\"}");
            return;
        }
        String response;
        int status = 200;
        switch (path) {
            case "/api/session", "/api/status" -> response = api.session();
            case "/api/devices" -> response = api.devices();
            case "/api/processes" -> response = api.processes(query);
            case "/api/catalog" -> {
                if (!"GET".equals(exchange.getRequestMethod())) {
                    send(exchange, 405, "application/json; charset=utf-8", "{\"error\":\"GET required\"}");
                    return;
                }
                response = api.catalog(query);
            }
            case "/api/capture" -> {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    send(exchange, 405, "application/json; charset=utf-8", "{\"error\":\"POST required\"}");
                    return;
                }
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                response = api.capture(body, query);
            }
            case "/api/mutate" -> {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    send(exchange, 405, "application/json; charset=utf-8", "{\"error\":\"POST required\"}");
                    return;
                }
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                response = api.mutate(body, query);
            }
            case "/api/highlight" -> {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    send(exchange, 405, "application/json; charset=utf-8", "{\"error\":\"POST required\"}");
                    return;
                }
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                response = api.highlight(body, query);
            }
            case "/api/jvmti-diag" -> {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    send(exchange, 405, "application/json; charset=utf-8", "{\"error\":\"POST required\"}");
                    return;
                }
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                response = api.jvmtiDiag(body, query);
            }
            case "/api/debug-restart" -> {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    send(exchange, 405, "application/json; charset=utf-8", "{\"error\":\"POST required\"}");
                    return;
                }
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                response = api.debugRestart(body, query);
            }
            default -> {
                status = 404;
                response = "{\"error\":\"Unknown API route\"}";
            }
        }
        send(exchange, status, "application/json; charset=utf-8", response);
    }

    private void serveResource(HttpExchange exchange, String requestPath) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            send(exchange, 405, "text/plain; charset=utf-8", "GET required.");
            return;
        }
        String path = "/".equals(requestPath) ? "index.html" : requestPath.substring(1);
        if (path.contains("..")) {
            send(exchange, 404, "text/plain; charset=utf-8", "Not found.");
            return;
        }
        byte[] content = resources.load(path);
        if (content == null) {
            send(exchange, 404, "text/plain; charset=utf-8", "Not found.");
            return;
        }
        send(exchange, 200, contentType(path), content);
    }

    private boolean sameOrigin(HttpExchange exchange) {
        List<String> origins = exchange.getRequestHeaders().get("Origin");
        if (origins == null || origins.isEmpty()) return true;
        String expected = baseUri().getScheme() + "://" + baseUri().getAuthority();
        return origins.size() == 1 && expected.equals(origins.get(0));
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> values = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) return values;
        for (String pair : raw.split("&")) {
            int separator = pair.indexOf('=');
            String key = separator < 0 ? pair : pair.substring(0, separator);
            String value = separator < 0 ? "" : pair.substring(separator + 1);
            values.put(decode(key), decode(value));
        }
        return values;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String contentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".js") || path.endsWith(".mjs")) return "text/javascript; charset=utf-8";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }

    private static void addSecurityHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().set(
                "Content-Security-Policy",
                "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; connect-src 'self'; frame-ancestors 'none'");
    }

    private static void send(HttpExchange exchange, int status, String contentType, String text) throws IOException {
        send(exchange, status, contentType, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    @Override
    public synchronized void close() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
        server = null;
        executor = null;
        baseUri = null;
    }

    public interface Api {
        String session();
        String devices();
        String processes(Map<String, String> query);
        String catalog(Map<String, String> query);
        String capture(String body, Map<String, String> query);
        String mutate(String body, Map<String, String> query);
        String highlight(String body, Map<String, String> query);
        String jvmtiDiag(String body, Map<String, String> query);
        String debugRestart(String body, Map<String, String> query);
    }

    @FunctionalInterface
    public interface ResourceLoader {
        byte[] load(String path) throws IOException;
    }
}
