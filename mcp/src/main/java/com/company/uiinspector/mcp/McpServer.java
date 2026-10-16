package com.company.uiinspector.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Minimal MCP server over stdio (newline-delimited JSON-RPC 2.0).
 * Implements initialize, tools/list and tools/call needed by standard MCP clients
 * (Claude Code, Codex, Android Studio Agent Mode, Cursor, etc.).
 */
public final class McpServer {
    static final String PROTOCOL_VERSION = "2025-06-18";
    static final String SERVER_NAME = "android-uiinspector-agent";
    static final String SERVER_VERSION = "0.1.0";

    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final McpTools tools;

    public McpServer(String baseUrl, String token) {
        this.tools = new McpTools(new InspectorClient(baseUrl, token));
    }

    McpServer(McpTools tools) {
        this.tools = tools;
    }

    public static void main(String[] args) {
        Config config = Config.from(args, System.getenv());
        McpServer server = new McpServer(config.baseUrl(), config.token());
        try {
            server.runLoop();
        } catch (IOException error) {
            System.err.println("uiinspector-mcp fatal: " + error.getMessage());
            System.exit(2);
        }
    }

    /** Reads JSON-RPC messages one per line from stdin and writes a response line to stdout. */
    void runLoop() throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintWriter out = new PrintWriter(new java.io.OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);
        String line;
        while ((line = in.readLine()) != null) {
            String response = handleLine(line);
            if (response != null) {
                out.println(response);
                out.flush();
            }
        }
    }

    /** Handles a single JSON-RPC line; returns the response JSON or null for notifications. */
    public String handleLine(String line) {
        if (line == null || line.isBlank()) return null;
        JsonObject request;
        try {
            request = JsonParser.parseString(line).getAsJsonObject();
        } catch (RuntimeException error) {
            return error(-32600, "Invalid JSON-RPC request: " + error.getMessage());
        }
        if (!request.has("method")) {
            // Notification or malformed; no valid method -> silently ignore unless id present.
            if (!request.has("id")) return null;
            return error(-32600, "Missing method.");
        }
        String method = request.get("method").getAsString();
        JsonElement idEl = request.get("id");
        boolean hasId = idEl != null;
        String id = hasId ? idEl.isJsonPrimitive() ? idEl.getAsString() : idEl.toString() : null;
        JsonObject params = request.get("params") != null && request.get("params").isJsonObject()
                ? request.getAsJsonObject("params") : new JsonObject();

        JsonElement result;
        try {
            switch (method) {
                case "initialize":
                    result = initialize();
                    break;
                case "notifications/initialized":
                    if (!hasId) return null;
                    result = ok();
                    break;
                case "ping":
                    result = ok();
                    break;
                case "tools/list":
                    result = toolsList();
                    break;
                case "tools/call":
                    result = toolsCall(params);
                    break;
                default:
                    return error(-32601, "Method not found: " + method);
            }
        } catch (Throwable error) {
            return error(-32603, "Internal error: " + safeMessage(error));
        }

        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        if (id != null) response.addProperty("id", id);
        response.add("result", result);
        return gson.toJson(response);
    }

    private JsonObject initialize() {
        JsonObject caps = new JsonObject();
        caps.add("tools", new JsonObject());
        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", SERVER_NAME);
        serverInfo.addProperty("version", SERVER_VERSION);
        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", PROTOCOL_VERSION);
        result.add("capabilities", caps);
        result.add("serverInfo", serverInfo);
        return result;
    }

    private JsonObject toolsList() {
        JsonArray arr = new JsonArray();
        for (McpTools.ToolSpec spec : tools.specs()) {
            JsonObject o = new JsonObject();
            o.addProperty("name", spec.name());
            o.addProperty("description", spec.description());
            o.add("inputSchema", spec.inputSchema());
            arr.add(o);
        }
        JsonObject result = new JsonObject();
        result.add("tools", arr);
        return result;
    }

    private JsonObject toolsCall(JsonObject params) throws Exception {
        String name = params.has("name") ? params.get("name").getAsString() : "";
        JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject()
                ? params.getAsJsonObject("arguments") : new JsonObject();
        JsonObject payload = tools.call(name, args);
        JsonObject content = new JsonObject();
        content.addProperty("type", "text");
        content.addProperty("text", payload.toString());
        JsonArray arr = new JsonArray();
        arr.add(content);
        JsonObject result = new JsonObject();
        result.add("content", arr);
        return result;
    }

    private static JsonObject ok() {
        return new JsonObject();
    }

    private String error(int code, String message) {
        JsonObject err = new JsonObject();
        err.addProperty("code", code);
        err.addProperty("message", message);
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("error", err);
        return gson.toJson(response);
    }

    private static String safeMessage(Throwable error) {
        return error == null || error.getMessage() == null || error.getMessage().isBlank()
                ? error == null ? "unknown" : error.getClass().getSimpleName()
                : error.getMessage();
    }

    record Config(String baseUrl, String token) {
        static Config from(String[] args, Map<String, String> env) {
            String base = env.get("UI_INSPECTOR_BASE_URL");
            String token = env.getOrDefault("UI_INSPECTOR_TOKEN", "");
            String url = null;
            String tok = null;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--base-url", "--base" -> { if (i + 1 < args.length) { base = args[++i]; } }
                    case "--token" -> { if (i + 1 < args.length) token = args[++i]; }
                    default -> { /* ignore unknown */ }
                }
            }
            if (base == null || base.isBlank()) {
                throw new IllegalArgumentException("Missing inspector base URL. Set UI_INSPECTOR_BASE_URL or pass --base-url <url>.");
            }
            return new Config(base.trim(), token == null ? "" : token.trim());
        }
    }
}
