package com.company.uiinspector.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;

/**
 * Stateless-ish MCP tool registry. Each tool reuses the plugin HTTP API and feeds
 * captured snapshots into {@link SnapshotIndex} for local query/locate/audit.
 */
public final class McpTools {
    public record ToolSpec(String name, String description, JsonObject inputSchema) {}

    private final InspectorClient client;
    private final PageInspector pageInspector;
    private volatile String snapshotJson;
    private final List<ToolSpec> specs = new ArrayList<>();

    private static final String SPEECH_ASSISTANT_PKG = "com.incall.apps.speechassistant";
    private static final String UI_PKG_DEFAULT = "com.changan.aiassist";
    private static final String WAKE_ACTION = "com.incall.action.SPEECH_WAKEUP";
    private static final String ASR_ACTION = "com.incall.action.TEXT_EXECUTE";
    private static final String WAKE_ASR_ACTION = "com.incall.apps.speechassistant.action.TEST_WAKE_ASR";

    public McpTools(InspectorClient client) {
        this.client = client;
        this.pageInspector = new PageInspector(client);
        register(tool("list_devices", "List connected Android devices (the plugin inspector backend).", schema()));
        register(tool("list_processes",
                "List running Android processes on a device (needs a serial from list_devices).",
                schema("serial")));
        register(tool("list_catalog",
                "List capture catalog items for a device+scope. scope: process|window|activity.",
                schema("serial", "scope")));
        register(tool("capture",
                "Capture a UI snapshot (View tree + screenshot). Defaults to scope=process; if only serial is provided, auto-targets the current project process. "
                        + "Returns a summary and stores the full snapshot for query_nodes/locate_node/accessibility_issues.",
                captureSchema()));
        register(tool("session", "Returns the current plugin inspector session and any cached snapshot.", schema()));
        register(tool("query_nodes",
                "Query nodes in the most recent snapshot. Returns matching nodes with ids, bounds, text and attributes.",
                querySchema()));
        register(tool("locate_node",
                "Locate a UI element by a natural-language description using the most recent snapshot. "
                        + "Returns ranked candidate nodes with matched terms and bounds.",
                schema("description", "limit")));
        register(tool("accessibility_issues",
                "List visible UI nodes missing text or content-description (accessibility audit) from the last snapshot.",
                schema("limit")));
        register(tool("list_page_targets",
                "List known page targets (labels + deep links) that inspect_page can launch.",
                schema()));
        register(tool("inspect_page",
                "Locate + start a page and summarize what it shows: resolve target description/deep-link, "
                        + "adb am start, wait, capture process view tree, and return visible text + interactive items.",
                pageSchema()));

        register(tool("trigger_wake",
                "Wake the speech assistant via its UiService (SPEECH_WAKEUP). Use before trigger_asr when the UI needs the voice layer active.",
                triggerWakeSchema()));
        register(tool("trigger_asr",
                "Inject one ASR text into the speech assistant (TEXT_EXECUTE). Does not capture; pair with capture/simulate_wake_asr.",
                triggerAsrSchema()));
        register(tool("simulate_wake_asr",
                "One-shot voice-command reproduction: wake speech assistant -> wait -> inject ASR text -> capture vpa UI tree and return visible/interactive items. Use when asked to verify UI driven by voice/ASR commands.",
                wakeAsrSchema()));

        register(tool("verify_ui",
                "High-level UI verification: automatically decides how to trigger the target UI before capturing. "
                        + "When a target/description deep-link is given it launches the Activity directly; when text is given "
                        + "it defaults to wake+ASR voice-command reproduction (trigger=asr for ASR-only); otherwise it just wakes "
                        + "the assistant. Returns decision + visible/interactive items of the target process.",
                verifyUiSchema()));
    }

    public List<ToolSpec> specs() { return specs; }

    public JsonObject call(String name, JsonObject args) throws Exception {
        switch (name) {
            case "list_devices":
                return ok(client.devices());
            case "list_processes":
                return ok(client.processes(requiredString(args, "serial")));
            case "list_catalog":
                return ok(client.catalog(requiredString(args, "serial"), str(args, "scope", "process")));
            case "capture":
                return ok(doCapture(args));
            case "session":
                return ok(doSession());
            case "query_nodes":
                return ok(doQuery(args));
            case "locate_node":
                return ok(doLocate(args));
            case "accessibility_issues":
                return ok(doAccessibility(args));
            case "list_page_targets":
                return ok(doListPageTargets());
            case "inspect_page":
                return ok(doInspectPage(args));
            case "trigger_wake":
                return ok(doTriggerWake(args));
            case "trigger_asr":
                return ok(doTriggerAsr(args));
            case "simulate_wake_asr":
                return ok(doSimulateWakeAsr(args));

            case "verify_ui":
                return ok(doVerifyUi(args));
            default:
                throw new IllegalArgumentException("Unknown tool: " + name);
        }
    }

    private String doCapture(JsonObject args) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("serial", requiredString(args, "serial"));
        String scope = str(args, "scope", "process");
        body.addProperty("scope", scope);
        boolean missingTarget = !args.has("packageName") && !args.has("processName") && !args.has("pid");
        if (missingTarget) {
            // 默认进程模式：只给 serial 时自动解析当前工程进程
            autoResolveProjectTarget(body, requiredString(args, "serial"));
        }
        if (args.has("packageName") && !args.get("packageName").isJsonNull())
            body.addProperty("packageName", args.get("packageName").getAsString());
        if (args.has("processName") && !args.get("processName").isJsonNull())
            body.addProperty("processName", args.get("processName").getAsString());
        if (args.has("pid") && !args.get("pid").isJsonNull())
            body.addProperty("pid", args.get("pid").getAsInt());
        if (args.has("windowIds") && args.get("windowIds").isJsonArray())
            body.add("windowIds", args.getAsJsonArray("windowIds"));
        if (args.has("activities") && args.get("activities").isJsonArray())
            body.add("activities", args.getAsJsonArray("activities"));
        String result = client.capture(body.toString());
        setSnapshot(result);
        // JVMTI 附加偶发失败：进程模式抓到空快照时自动重试一次
        boolean processScope = "process".equalsIgnoreCase(scope);
        if (processScope && processSnapshotEmpty()) {
            try {
                Thread.sleep(1500L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            result = client.capture(body.toString());
            setSnapshot(result);
        }
        return summary("Captured " + describeCount(), result);
    }

    private boolean processSnapshotEmpty() {
        try {
            SnapshotIndex idx = index();
            return idx.windows().isEmpty() && idx.nodeCount() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void autoResolveProjectTarget(JsonObject body, String serial) throws Exception {
        String defaultPackage = null;
        try {
            JsonObject root = JsonParser.parseString(client.session()).getAsJsonObject();
            if (root.has("defaultPackage") && !root.get("defaultPackage").isJsonNull()) {
                defaultPackage = root.get("defaultPackage").getAsString();
            }
        } catch (Exception ignored) {
            // session unavailable is not fatal; fall back to first process below
        }
        String catalogJson = client.catalog(serial, "process");
        JsonElement parsed = JsonParser.parseString(catalogJson);
        JsonArray arr = parsed.isJsonArray() ? parsed.getAsJsonArray()
                : parsed.isJsonObject() && parsed.getAsJsonObject().has("data")
                    ? parsed.getAsJsonObject().getAsJsonArray("data") : null;
        if (arr == null || arr.isEmpty()) return;
        JsonObject chosen = pickProjectProcess(arr, defaultPackage);
        if (chosen == null) return;
        if (chosen.has("packageName") && !chosen.get("packageName").isJsonNull())
            body.addProperty("packageName", chosen.get("packageName").getAsString());
        if (chosen.has("processName") && !chosen.get("processName").isJsonNull())
            body.addProperty("processName", chosen.get("processName").getAsString());
        if (chosen.has("pid") && chosen.get("pid").isJsonPrimitive())
            body.addProperty("pid", chosen.get("pid").getAsInt());
    }

    private static JsonObject pickProjectProcess(JsonArray arr, String defaultPackage) {
        if (defaultPackage != null && !defaultPackage.isBlank()) {
            String target = defaultPackage.trim();
            for (JsonElement e : arr) {
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                String pkg = o.has("packageName") && !o.get("packageName").isJsonNull()
                        ? o.get("packageName").getAsString() : "";
                String proc = o.has("processName") && !o.get("processName").isJsonNull()
                        ? o.get("processName").getAsString() : "";
                if (pkg.equalsIgnoreCase(target) || proc.equalsIgnoreCase(target)) return o;
            }
        }
        if (!arr.isEmpty() && arr.get(0).isJsonObject()) return arr.get(0).getAsJsonObject();
        return null;
    }

    private String doListPageTargets() {
        JsonObject out = new JsonObject();
        out.add("targets", PageInspector.knownTargets());
        return out.toString();
    }

    private String doInspectPage(JsonObject args) throws Exception {
        String serial = str(args, "serial", null);
        if (serial == null || serial.isBlank()) {
            JsonElement devices = JsonParser.parseString(client.devices());
            if (devices.isJsonArray() && devices.getAsJsonArray().size() > 0) {
                JsonObject d = devices.getAsJsonArray().get(0).getAsJsonObject();
                serial = d.has("serial") ? d.get("serial").getAsString() : null;
            }
        }
        if (serial == null || serial.isBlank()) {
            throw new IllegalArgumentException("serial is required (or pass an adb-connected device)");
        }
        String packageName = str(args, "packageName", "com.changan.aiassist");
        String processName = str(args, "processName", packageName);
        String target = str(args, "target", null);
        if (target == null || target.isBlank()) target = str(args, "description", null);
        String deepLink = PageInspector.resolveDeepLink(target);
        JsonObject result = new JsonObject();
        result.addProperty("serial", serial);
        result.addProperty("targetResolved", deepLink);
        result.add("knownTargets", PageInspector.knownTargets());
        if (deepLink == null) {
            result.addProperty("ok", false);
            result.addProperty("error", "Cannot resolve target. Use e.g. '设置页'/'首页' or a deep link URL.");
            return result.toString();
        }
        result.addProperty("startStatus", pageInspector.startAndWait(
                serial, deepLink, packageName, intOr(args, "waitMs", 8000)));
        String snapshot = pageInspector.captureProcessSnapshot(serial, packageName, processName);
        setSnapshot(snapshot);
        result.addProperty("ok", true);
        result.add("summary", PageInspector.extractItems(snapshot));
        return result.toString();
    }

    /** UiService ????? TEST_WAKE_ASR / TEXT_EXECUTE ?????????????? */
    private String ensureSpeechAssistantStarted(String serial) throws Exception {
        String start = pageInspector.runAdb(List.of("-s", serial, "shell",
                "am", "startservice", "-n", SPEECH_ASSISTANT_PKG + "/.service.UiService")).trim();
        Thread.sleep(700L);
        return start;
    }

    private String doTriggerWake(JsonObject args) throws Exception {
        String serial = resolveSerial(args);
        String tts = str(args, "tts", "\u5c0f\u52a9\u5728");
        int direction = intOr(args, "direction", 1);
        String cmd = "am startservice -n " + SPEECH_ASSISTANT_PKG + "/.service.UiService"
                + " -a " + WAKE_ACTION
                + " --es tts " + shellQuote(tts)
                + " --ei direction " + direction;
        String output = pageInspector.runAdb(List.of("-s", serial, "shell", cmd)).trim();
        Thread.sleep(intOr(args, "settleMs", 800));
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("serial", serial);
        out.addProperty("command", cmd);
        out.addProperty("adbOutput", output);
        return out.toString();
    }

    private String doTriggerAsr(JsonObject args) throws Exception {
        String serial = resolveSerial(args);
        String started = ensureSpeechAssistantStarted(serial);
        String text = requiredString(args, "text");
        int mode = intOr(args, "mode", 3);
        String cmd = "am broadcast -a " + ASR_ACTION
                + " --es text " + shellQuote(text)
                + " --ei type 0"
                + " --ei mode " + mode;
        String direct = str(args, "direct", null);
        if (direct != null && !direct.isBlank()) cmd += " --es direct " + shellQuote(direct);
        String output = pageInspector.runAdb(List.of("-s", serial, "shell", cmd)).trim();
        JsonObject out = new JsonObject();
        out.addProperty("serviceStarted", started);
        out.addProperty("ok", true);
        out.addProperty("serial", serial);
        out.addProperty("command", cmd);
        out.addProperty("adbOutput", output);
        return out.toString();
    }

    private String doSimulateWakeAsr(JsonObject args) throws Exception {
        String serial = resolveSerial(args);
        clearLogcat(serial);
        String started = ensureSpeechAssistantStarted(serial);
        String text = requiredString(args, "text");
        int direction = intOr(args, "direction", 1);
        int mode = intOr(args, "mode", 3);
        int wakeWaitMs = intOr(args, "waitMs", 1500);
        String tts = str(args, "tts", "\u5c0f\u52a9\u5728");
        String packageName = str(args, "packageName", UI_PKG_DEFAULT);
        String processName = str(args, "processName", packageName);
        String cmd = "am broadcast -a " + WAKE_ASR_ACTION
                + " --es text " + shellQuote(text)
                + " --ei direction " + direction
                + " --ei mode " + mode
                + " --ei waitMs " + wakeWaitMs
                + " --es tts " + shellQuote(tts);
        String output = pageInspector.runAdb(List.of("-s", serial, "shell", cmd)).trim();
        if (!waitForPgsLog(serial, text, intOr(args, "logWaitMs", 10000))) {
            sleepQuietly(intOr(args, "settleMs", Math.max(2000, wakeWaitMs + 800)));
        } else {
            sleepQuietly(250L);
        }
        String snapshot = pageInspector.captureProcessSnapshot(serial, packageName, processName);
        setSnapshot(snapshot);
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("serviceStarted", started);
        result.addProperty("text", text);
        result.addProperty("wakeWaitMs", wakeWaitMs);
        result.addProperty("broadcastOutput", output);
        result.add("summary", PageInspector.extractItems(snapshot));
        return result.toString();
    }


    private String doVerifyUi(JsonObject args) throws Exception {
        String serial = resolveSerial(args);
        String trigger = str(args, "trigger", "auto");
        String target = str(args, "target", null);
        if (target == null || target.isBlank()) target = str(args, "description", null);
        String text = str(args, "text", null);
        String packageName = str(args, "packageName", UI_PKG_DEFAULT);
        String processName = str(args, "processName", packageName);
        String deepLink = target == null || target.isBlank() ? null : PageInspector.resolveDeepLink(target);
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("serial", serial);
        result.addProperty("trigger", trigger);
        result.addProperty("decision", "none");
        result.addProperty("packageName", packageName);
        result.addProperty("processName", processName);
        if (target != null && !target.isBlank()) {
            result.addProperty("target", target);
            result.addProperty("targetResolved", deepLink == null ? "unresolved" : deepLink);
        }
        result.add("knownTargets", PageInspector.knownTargets());

        // 1) ?? deep link / Activity ??????????????
        if (deepLink != null) {
            result.addProperty("decision", "activity");
            result.addProperty("startStatus", pageInspector.startAndWait(
                    serial, deepLink, packageName, intOr(args, "waitMs", 8000)));
            String snapshot = captureWithRetry(serial, packageName, processName);
            setSnapshot(snapshot);
            addTextMatchFlags(result, snapshot, text, target);
            result.add("summary", PageInspector.extractItems(snapshot));
            return result.toString();
        }

        clearLogcat(serial);
        boolean voiceOk = text != null && !text.isBlank();
        boolean doWakeAsr = voiceOk && ("auto".equalsIgnoreCase(trigger) || "wake_asr".equalsIgnoreCase(trigger));
        boolean doAsrOnly = voiceOk && "asr".equalsIgnoreCase(trigger);

        // 2) ??/ASR ?????+?? ASR ???????? vpa UI?
        if (doWakeAsr || doAsrOnly) {
            if (doWakeAsr) {
                result.addProperty("decision", "wake_asr");
                String started = ensureSpeechAssistantStarted(serial);
                int wakeWaitMs = intOr(args, "waitMs", 1500);
                String cmd = "am broadcast -a " + WAKE_ASR_ACTION
                        + " --es text " + shellQuote(text)
                        + " --ei direction " + intOr(args, "direction", 1)
                        + " --ei mode " + intOr(args, "mode", 3)
                        + " --ei waitMs " + wakeWaitMs
                        + " --es tts " + shellQuote(str(args, "tts", "\u5c0f\u52a9\u5728"));
                String output = pageInspector.runAdb(List.of("-s", serial, "shell", cmd)).trim();
                result.addProperty("serviceStarted", started);
                result.addProperty("broadcastOutput", output);
                if (!waitForPgsLog(serial, text, intOr(args, "logWaitMs", 10000))) {
                    sleepQuietly(intOr(args, "settleMs", Math.max(2000, wakeWaitMs + 800)));
                } else {
                    // PgsManager ???? dispatch ?????2s ?????????
                    sleepQuietly(250L);
                }
            } else {
                result.addProperty("decision", "asr");
                String started = ensureSpeechAssistantStarted(serial);
                String direct = str(args, "direct", null);
                String cmd = "am broadcast -a " + ASR_ACTION
                        + " --es text " + shellQuote(text)
                        + " --ei type 0"
                        + " --ei mode " + intOr(args, "mode", 3);
                if (direct != null && !direct.isBlank()) cmd += " --es direct " + shellQuote(direct);
                String output = pageInspector.runAdb(List.of("-s", serial, "shell", cmd)).trim();
                result.addProperty("serviceStarted", started);
                result.addProperty("broadcastOutput", output);
                Thread.sleep(intOr(args, "settleMs", 2500));
            }
            String snapshot = captureWithRetry(serial, packageName, processName);
            setSnapshot(snapshot);
            addTextMatchFlags(result, snapshot, text, target);
            result.add("summary", PageInspector.extractItems(snapshot));
            return result.toString();
        }

        // 3) ?????? vpa UI ?????????????????
        result.addProperty("decision", "wake");
        String cmd = "am startservice -n " + SPEECH_ASSISTANT_PKG + "/.service.UiService"
                + " -a " + WAKE_ACTION
                + " --es tts " + shellQuote(str(args, "tts", "\u5c0f\u52a9\u5728"))
                + " --ei direction " + intOr(args, "direction", 1);
        String output = pageInspector.runAdb(List.of("-s", serial, "shell", cmd)).trim();
        result.addProperty("broadcastOutput", output);
        Thread.sleep(intOr(args, "settleMs", 2000));
        String snapshot = captureWithRetry(serial, packageName, processName);
        setSnapshot(snapshot);
        addTextMatchFlags(result, snapshot, text, target);
        result.add("summary", PageInspector.extractItems(snapshot));
        return result.toString();
    }

    private void addTextMatchFlags(JsonObject result, String snapshot, String asrText, String target) {
        boolean asrFound = asrText != null && !asrText.isBlank() && snapshot != null
                && snapshot.contains(asrText);
        result.addProperty("asrTextFound", asrFound);
        String tgt = target == null ? null : target.replaceAll("[\u5c0f\u52a9\u5728\u6253\u5f00\u8bbe\u7f6e\u9875\u8fd4\u56de\u591a\u5c11\u4e2a\u4ec0\u4e48\u8bf7\u70b9\u51fb\u97f3\u91cf\u660e\u4eae\u5173\u95ed\u5f00\u542f\u7a97\u53e3\u5f53\u524d]", "").strip();
        boolean targetFound = tgt != null && !tgt.isBlank() && snapshot != null && snapshot.contains(tgt);
        result.addProperty("targetTextFound", targetFound && !asrFound);
    }



    private void clearLogcat(String serial) throws Exception {
        try {
            pageInspector.runAdb(List.of("-s", serial, "logcat", "-c"));
        } catch (Exception ignored) {
            // clearing logcat is best-effort; ignore failures
        }
    }

    private boolean waitForPgsLog(String serial, String text, int timeoutMs) throws Exception {
        if (text == null || text.isBlank()) return false;
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String output = pageInspector.runAdb(
                    List.of("-s", serial, "logcat", "-d", "-t", "800")).toLowerCase();
            if (output.contains("pgsmanager") && output.contains("dispatchtoasr")) {
                // ???????????
                String needle = text.toLowerCase();
                String[] lines = output.split("\\n");
                for (String line : lines) {
                    if (line.contains("dispatchtoasr") && line.contains(needle)) {
                        return true;
                    }
                }
            }
            try {
                Thread.sleep(250L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private String captureWithRetry(String serial, String packageName, String processName) throws Exception {
        String snapshot = pageInspector.captureProcessSnapshot(serial, packageName, processName);
        if (snapshot == null || snapshot.isBlank()) {
            Thread.sleep(1200L);
            snapshot = pageInspector.captureProcessSnapshot(serial, packageName, processName);
        }
        return snapshot;
    }

    private String resolveSerial(JsonObject args) throws Exception {
        String serial = str(args, "serial", null);
        if (serial == null || serial.isBlank()) {
            JsonElement devices = JsonParser.parseString(client.devices());
            if (devices.isJsonArray() && devices.getAsJsonArray().size() > 0) {
                JsonObject d = devices.getAsJsonArray().get(0).getAsJsonObject();
                serial = d.has("serial") ? d.get("serial").getAsString() : null;
            }
        }
        if (serial == null || serial.isBlank()) {
            throw new IllegalArgumentException("serial is required (or pass an adb-connected device)");
        }
        return serial;
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static JsonObject triggerWakeSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        addStr(props, "serial");
        addStr(props, "tts");
        addInt(props, "direction");
        addInt(props, "settleMs");
        schema.add("properties", props);
        return schema;
    }

    private static JsonObject triggerAsrSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        addStr(props, "serial");
        addStr(props, "text");
        addStr(props, "direct");
        addInt(props, "mode");
        schema.add("properties", props);
        return schema;
    }


    private static JsonObject verifyUiSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        addStr(props, "serial");
        addStr(props, "trigger");
        addStr(props, "target");
        addStr(props, "description");
        addStr(props, "text");
        addStr(props, "packageName");
        addStr(props, "processName");
        addStr(props, "tts");
        addStr(props, "direct");
        addInt(props, "direction");
        addInt(props, "mode");
        addInt(props, "waitMs");
        addInt(props, "settleMs");
        addInt(props, "logWaitMs");
        schema.add("properties", props);
        return schema;
    }

    private static JsonObject wakeAsrSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        addStr(props, "serial");
        addStr(props, "text");
        addStr(props, "packageName");
        addStr(props, "processName");
        addStr(props, "tts");
        addStr(props, "direct");
        addInt(props, "direction");
        addInt(props, "mode");
        addInt(props, "waitMs");
        addInt(props, "settleMs");
        addInt(props, "logWaitMs");
        schema.add("properties", props);
        return schema;
    }

    private static JsonObject pageSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        addStr(props, "serial");
        addStr(props, "target");
        addStr(props, "description");
        addStr(props, "packageName");
        addStr(props, "processName");
        JsonObject wait = new JsonObject();
        wait.addProperty("type", "integer");
        props.add("waitMs", wait);
        schema.add("properties", props);
        return schema;
    }

    private String doSession() throws Exception {
        String json = client.session();
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (root.has("snapshot") && root.get("snapshot").isJsonObject()
                && !root.get("snapshot").getAsJsonObject().isEmpty()) {
            setSnapshot(root.getAsJsonObject("snapshot").toString());
        }
        JsonObject out = new JsonObject();
        out.addProperty("status", root.has("status") ? root.get("status").getAsString() : "unknown");
        out.addProperty("defaultPackage", root.has("defaultPackage") && !root.get("defaultPackage").isJsonNull()
                ? root.get("defaultPackage").getAsString() : null);
        out.addProperty("hasSnapshot", snapshotJson != null);
        out.addProperty("windows", snapshotJson == null ? 0 : index().windows().size());
        out.addProperty("nodes", snapshotJson == null ? 0 : index().nodeCount());
        return out.toString();
    }

    private String doQuery(JsonObject args) throws Exception {
        ensureSnapshot();
        SnapshotIndex idx = index();
        List<SnapshotIndex.Node> matches = idx.query(
                str(args, "text", null), str(args, "className", null), str(args, "resourceId", null),
                bool(args, "noContentDescription", false), bool(args, "visibleOnly", false),
                str(args, "windowId", null), intOr(args, "limit", 50));
        JsonArray arr = new JsonArray();
        for (SnapshotIndex.Node n : matches) arr.add(idx.describeNode(n));
        JsonObject out = new JsonObject();
        out.addProperty("count", matches.size());
        out.add("matches", arr);
        return out.toString();
    }

    private String doLocate(JsonObject args) throws Exception {
        ensureSnapshot();
        SnapshotIndex idx = index();
        int limit = intOr(args, "limit", 5);
        List<SnapshotIndex.LocateHit> hits = idx.locate(requiredString(args, "description"), limit);
        JsonArray arr = new JsonArray();
        for (SnapshotIndex.LocateHit hit : hits) arr.add(hit.describe());
        JsonObject out = new JsonObject();
        out.addProperty("description", str(args, "description", ""));
        out.addProperty("count", hits.size());
        out.add("candidates", arr);
        return out.toString();
    }

    private String doAccessibility(JsonObject args) throws Exception {
        ensureSnapshot();
        SnapshotIndex idx = index();
        List<SnapshotIndex.Node> issues = idx.accessibilityIssues(intOr(args, "limit", 50));
        JsonArray arr = new JsonArray();
        for (SnapshotIndex.Node n : issues) arr.add(idx.describeNode(n));
        JsonObject out = new JsonObject();
        out.addProperty("count", issues.size());
        out.addProperty("hint", "Nodes listed here are visible but have neither text nor contentDescription.");
        out.add("nodes", arr);
        return out.toString();
    }

    private void ensureSnapshot() throws Exception {
        if (snapshotJson == null) {
            doSession();
        }
        if (snapshotJson == null) {
            throw new IllegalStateException("No snapshot available. Run capture() first, or open the inspector and capture there.");
        }
    }

    private void setSnapshot(String json) {
        if (json == null || json.isBlank()) return;
        this.snapshotJson = json;
    }

    private SnapshotIndex index() {
        return SnapshotIndex.fromJson(snapshotJson);
    }

    private String describeCount() {
        SnapshotIndex idx = index();
        return idx.windows().size() + " windows, " + idx.nodeCount() + " nodes.";
    }

    private String summary(String message, String raw) {
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("message", message);
        return out.toString();
    }

    private static JsonObject ok(String text) {
        if (text == null || text.isBlank()) return new JsonObject();
        JsonElement element = JsonParser.parseString(text);
        if (element.isJsonObject()) return element.getAsJsonObject();
        JsonObject wrapped = new JsonObject();
        wrapped.add("data", element);
        return wrapped;
    }

    private static String requiredString(JsonObject o, String key) {
        String v = str(o, key, null);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("Missing required argument: " + key);
        return v;
    }

    private static String str(JsonObject o, String key, String fallback) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : fallback;
    }

    private static boolean bool(JsonObject o, String key, boolean fallback) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsBoolean() : fallback;
    }

    private static int intOr(JsonObject o, String key, int fallback) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : fallback;
    }

    private static JsonObject schema(String... required) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        for (String name : required) {
            JsonObject p = new JsonObject();
            p.addProperty("type", "string");
            props.add(name, p);
        }
        schema.add("properties", props);
        return schema;
    }

    private static JsonObject captureSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        addStr(props, "serial");
        addStr(props, "scope");
        addStr(props, "packageName");
        addStr(props, "processName");
        JsonObject pid = new JsonObject(); pid.addProperty("type", "integer"); props.add("pid", pid);
        schema.add("properties", props);
        return schema;
    }

    private static JsonObject querySchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        addStr(props, "text");
        addStr(props, "className");
        addStr(props, "resourceId");
        addStr(props, "windowId");
        addBool(props, "noContentDescription");
        addBool(props, "visibleOnly");
        JsonObject limit = new JsonObject(); limit.addProperty("type", "integer"); props.add("limit", limit);
        schema.add("properties", props);
        return schema;
    }

    private static void addStr(JsonObject props, String name) {
        JsonObject p = new JsonObject();
        p.addProperty("type", "string");
        props.add(name, p);
    }

    private static void addBool(JsonObject props, String name) {
        JsonObject p = new JsonObject();
        p.addProperty("type", "boolean");
        props.add(name, p);
    }

    private static void addInt(JsonObject props, String name) {
        JsonObject p = new JsonObject();
        p.addProperty("type", "integer");
        props.add(name, p);
    }

    private static ToolSpec tool(String name, String description, JsonObject schema) {
        return new ToolSpec(name, description, schema);
    }

    private static void register(McpTools self, ToolSpec spec) {
        self.specs.add(spec);
    }

    private void register(ToolSpec spec) {
        this.specs.add(spec);
    }
}
