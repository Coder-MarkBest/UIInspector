package com.company.uiinspector.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;

class McpVerificationTest {

    static final String SNAPSHOT = """
    {
      "schemaVersion": 1,
      "capturedAtEpochMs": 1750000000000,
      "deviceSerial": "emulator-5554",
      "scope": "PROCESS_WINDOWS",
      "targetPackage": "com.example.app",
      "targetProcess": "com.example.app",
      "processes": [],
      "windows": [
        {"id": "w1", "source": "SEMANTIC", "packageName": "com.example.app", "processName": "com.example.app", "displayId": 0, "type": "APPLICATION", "layer": 1, "parentWindowId": null, "childWindowIds": [], "bounds": {"left": 0, "top": 0, "right": 1080, "bottom": 2340}, "active": true, "focused": true, "title": "MainActivity", "rootNodeId": "n1", "propertyGroups": []}
      ],
      "nodes": {
        "n0": {"id": "n0", "windowId": "w1", "parentId": null, "childIds": ["n1", "n2"], "depth": 0, "className": "android.widget.LinearLayout", "resourceId": "", "text": "", "contentDescription": "", "bounds": {"left": 0, "top": 0, "right": 1080, "bottom": 2340}, "visibleBounds": {"left": 0, "top": 0, "right": 1080, "bottom": 2340}, "drawingOrder": 0, "propertyGroups": []},
        "n1": {"id": "n1", "windowId": "w1", "parentId": "n0", "childIds": [], "depth": 1, "className": "android.widget.Button", "resourceId": "com.example.app:id/submit", "text": "提交", "contentDescription": "提交订单", "bounds": {"left": 40, "top": 100, "right": 300, "bottom": 160}, "visibleBounds": {"left": 40, "top": 100, "right": 300, "bottom": 160}, "drawingOrder": 1, "propertyGroups": []},
        "n2": {"id": "n2", "windowId": "w1", "parentId": "n0", "childIds": [], "depth": 1, "className": "android.widget.ImageView", "resourceId": "com.example.app:id/icon", "text": "", "contentDescription": "", "bounds": {"left": 400, "top": 200, "right": 500, "bottom": 300}, "visibleBounds": {"left": 400, "top": 200, "right": 500, "bottom": 300}, "drawingOrder": 2, "propertyGroups": []},
        "n3": {"id": "n3", "windowId": "w1", "parentId": "n0", "childIds": [], "depth": 1, "className": "android.widget.TextView", "resourceId": "com.example.app:id/header", "text": "订单中心", "contentDescription": "", "bounds": {"left": 40, "top": 30, "right": 400, "bottom": 70}, "visibleBounds": {"left": 40, "top": 30, "right": 400, "bottom": 70}, "drawingOrder": 3, "propertyGroups": []}
      },
      "diagnostics": [],
      "screenshot": null
    }
    """;

    private static final String EMPTY_SNAPSHOT = """
    {
      "schemaVersion": 1,
      "capturedAtEpochMs": 1750000000000,
      "deviceSerial": "emulator-5554",
      "scope": "PROCESS_WINDOWS",
      "targetPackage": "com.example.app",
      "targetProcess": "com.example.app",
      "processes": [],
      "windows": [],
      "nodes": {},
      "diagnostics": [],
      "screenshot": null
    }
    """;

    private HttpServer server;
    private ExecutorService executor;
    private McpServer mcp;
    private volatile String lastCaptureBody;
    private int captureEmptyResponsesRemaining;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/api/", this::handleApi);
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        mcp = new McpServer(base, "test-token");
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    private void handleApi(HttpExchange exchange) throws java.io.IOException {
        String path = exchange.getRequestURI().getPath();
        String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String json = "{}";
        switch (path) {
            case "/api/session" -> json = "{\"status\":\"ready\",\"defaultPackage\":\"com.example.app\",\"projectPackages\":[\"com.example.app\"],\"snapshot\":null}";
            case "/api/devices" -> json = "[{\"serial\":\"emulator-5554\",\"state\":\"device\",\"model\":\"Pixel 7\",\"ready\":true}]";
            case "/api/processes" -> json = "[{\"pid\":1234,\"user\":\"u0_a1\",\"packageName\":\"com.example.app\",\"processName\":\"com.example.app\",\"debuggable\":true}]";
            case "/api/catalog" -> json = "[{\"type\":\"process\",\"id\":\"1234\",\"pid\":1234,\"packageName\":\"com.example.app\",\"processName\":\"com.example.app\",\"label\":\"com.example.app\"}]";
            case "/api/capture" -> {
                lastCaptureBody = requestBody;
                if (captureEmptyResponsesRemaining > 0) { captureEmptyResponsesRemaining--; json = EMPTY_SNAPSHOT; }
                else { json = SNAPSHOT; }
            }
            default -> json = "{\"error\":\"not found: " + path + "\"}";
        }
        byte[] out = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, out.length);
        exchange.getResponseBody().write(out);
        exchange.close();
    }

    private String call(String line) {
        return mcp.handleLine(line);
    }

    @Test
    void initReturnsCapabilitiesAndToolsList() {
        callInit();
        JsonObject list = JsonParser.parseString(call("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}")).getAsJsonObject();
        Assertions.assertTrue(list.getAsJsonObject("result").getAsJsonArray("tools").size() >= 8);
    }

    @Test
    void listDevicesThroughMcpTool() {
        callInit();
        String response = call("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"list_devices\",\"arguments\":{}}}");
        JsonObject res = JsonParser.parseString(response).getAsJsonObject();
        Assertions.assertTrue(res.has("result"), "expected result, got: " + response);
    }

    @Test
    void captureThenQueryNodesFindsRealNodes() {
        callInit();
        call("{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"tools/call\",\"params\":{\"name\":\"capture\",\"arguments\":{\"serial\":\"emulator-5554\",\"scope\":\"process\"}}}");
        String response = call("{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"tools/call\",\"params\":{\"name\":\"query_nodes\",\"arguments\":{\"text\":\"提交\"}}}");
        String text = resultText(response);
        JsonObject payload = JsonParser.parseString(text).getAsJsonObject();
        Assertions.assertTrue(payload.get("count").getAsInt() >= 1, "expected nodes, got " + text);
    }

    @Test
    void locateNodeByNaturalLanguagePicksSubmitButton() {
        callInit();
        call("{\"jsonrpc\":\"2.0\",\"id\":20,\"method\":\"tools/call\",\"params\":{\"name\":\"capture\",\"arguments\":{\"serial\":\"emulator-5554\",\"scope\":\"process\"}}}");
        String response = call("{\"jsonrpc\":\"2.0\",\"id\":21,\"method\":\"tools/call\",\"params\":{\"name\":\"locate_node\",\"arguments\":{\"description\":\"提交订单按钮\"}}}");
        String text = resultText(response);
        JsonObject payload = JsonParser.parseString(text).getAsJsonObject();
        Assertions.assertTrue(payload.get("count").getAsInt() >= 1, "expected locate hit, got " + text);
        String nodeId = payload.getAsJsonArray("candidates").get(0).getAsJsonObject().get("nodeId").getAsString();
        Assertions.assertEquals("n1", nodeId);
    }

    @Test
    void accessibilityIssuesListMissingDescriptions() {
        callInit();
        call("{\"jsonrpc\":\"2.0\",\"id\":30,\"method\":\"tools/call\",\"params\":{\"name\":\"capture\",\"arguments\":{\"serial\":\"emulator-5554\",\"scope\":\"process\"}}}");
        String response = call("{\"jsonrpc\":\"2.0\",\"id\":31,\"method\":\"tools/call\",\"params\":{\"name\":\"accessibility_issues\",\"arguments\":{}}}");
        String text = resultText(response);
        JsonObject payload = JsonParser.parseString(text).getAsJsonObject();
        Assertions.assertTrue(payload.get("count").getAsInt() >= 1, "expected accessibility issues, got " + text);
        String nodeId = payload.getAsJsonArray("nodes").get(0).getAsJsonObject().get("nodeId").getAsString();
        Assertions.assertEquals("n2", nodeId);
    }

    @Test
    void captureDefaultsProcessScopeAndAutoResolvesProjectProcess() throws Exception {
        callInit();
        String response = call("{\"jsonrpc\":\"2.0\",\"id\":40,\"method\":\"tools/call\",\"params\":{\"name\":\"capture\",\"arguments\":{\"serial\":\"emulator-5554\"}}}");
        org.junit.jupiter.api.Assertions.assertTrue(response.contains("result"), "expected result, got: " + response);
        org.junit.jupiter.api.Assertions.assertNotNull(lastCaptureBody, "capture body was not recorded");
        var body = JsonParser.parseString(lastCaptureBody).getAsJsonObject();
        org.junit.jupiter.api.Assertions.assertEquals("process", body.get("scope").getAsString());
        org.junit.jupiter.api.Assertions.assertEquals("com.example.app", body.get("packageName").getAsString());
        org.junit.jupiter.api.Assertions.assertEquals("com.example.app", body.get("processName").getAsString());
        org.junit.jupiter.api.Assertions.assertEquals(1234, body.get("pid").getAsInt());
    }

    @Test
    void captureRetriesEmptyProcessSnapshotOnce() throws Exception {
        captureEmptyResponsesRemaining = 1;
        callInit();
        String response = call("{\"jsonrpc\":\"2.0\",\"id\":50,\"method\":\"tools/call\",\"params\":{\"name\":\"capture\",\"arguments\":{\"serial\":\"emulator-5554\"}}}");
        String text = resultText(response);
        org.junit.jupiter.api.Assertions.assertTrue(text.contains("Captured 1 windows, 4 nodes."),
                "expected retried capture, got: " + text);
        org.junit.jupiter.api.Assertions.assertEquals(0, captureEmptyResponsesRemaining);
    }

    private void callInit() {
        call("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"verify\",\"version\":\"1\"}}}");
    }

    private static String resultText(String response) {
        JsonObject res = JsonParser.parseString(response).getAsJsonObject();
        Assertions.assertTrue(res.has("result"), "expected result, got: " + response);
        return res.getAsJsonObject("result").getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
    }
}