package com.company.uiinspector.plugin.server;

import com.company.uiinspector.plugin.adb.AdbClient;
import com.company.uiinspector.plugin.adb.CollectorManager;
import com.company.uiinspector.plugin.adb.DeviceCatalog;
import com.company.uiinspector.plugin.adb.ProcessCatalog;
import com.company.uiinspector.plugin.adb.RunningProcess;
import com.company.uiinspector.plugin.capture.AgentDeployer;
import com.company.uiinspector.plugin.capture.ScreenshotCapture;
import com.company.uiinspector.plugin.capture.SemanticCapture;
import com.company.uiinspector.plugin.capture.SnapshotMerger;
import com.company.uiinspector.protocol.CaptureScope;
import com.company.uiinspector.protocol.CaptureSource;
import com.company.uiinspector.protocol.Diagnostic;
import com.company.uiinspector.protocol.InspectionSnapshot;
import com.company.uiinspector.protocol.MutationCommand;
import com.company.uiinspector.protocol.MutationJson;
import com.company.uiinspector.protocol.MutationResult;
import com.company.uiinspector.protocol.ProcessInfo;
import com.company.uiinspector.protocol.PropertyGroup;
import com.company.uiinspector.protocol.PropertyValue;
import com.company.uiinspector.protocol.Rect;
import com.company.uiinspector.protocol.Screenshot;
import com.company.uiinspector.protocol.SnapshotJson;
import com.company.uiinspector.protocol.UiNode;
import com.company.uiinspector.protocol.WindowSnapshot;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

public final class InspectorApi implements InspectorServer.Api {
    private static final Gson GSON = new Gson();

    private static final Pattern WINDOW_PATTERN =
            Pattern.compile("Window\\{(.*)\\}");
    private static final Pattern ACTIVITY_PATTERN =
            Pattern.compile("ACTIVITY\\s+([\\w./]+)");

    private final AdbClient adb;
    private final DeviceCatalog devices;
    private final ProcessCatalog processes;
    private final CollectorManager collector;
    private final SemanticCapture semantic;
    private final ScreenshotCapture screenshots;
    private final AgentDeployer agents;
    private final String defaultPackage;
    private final List<String> projectPackages;
    private final AtomicReference<InspectionSnapshot> current = new AtomicReference<>();
    private final AtomicReference<String> status = new AtomicReference<>("idle");
    private final ExecutorService captureExecutor = Executors.newFixedThreadPool(3, r -> {
        Thread thread = new Thread(r, "uiinspector-capture");
        thread.setDaemon(true);
        return thread;
    });

    public InspectorApi(
            AdbClient adb,
            CollectorManager collector,
            AgentDeployer agents,
            String defaultPackage) {
        this(adb, collector, agents,
                defaultPackage == null || defaultPackage.isBlank()
                        ? List.of() : List.of(defaultPackage));
    }

    public InspectorApi(
            AdbClient adb,
            CollectorManager collector,
            AgentDeployer agents,
            List<String> projectPackages) {
        this.adb = adb;
        this.devices = new DeviceCatalog(adb);
        this.processes = new ProcessCatalog(adb);
        this.collector = collector;
        this.semantic = new SemanticCapture(adb);
        this.screenshots = new ScreenshotCapture(adb);
        this.agents = agents;
        this.projectPackages = projectPackages == null ? List.of() : List.copyOf(projectPackages);
        this.defaultPackage = this.projectPackages.isEmpty() ? null : this.projectPackages.get(0);
    }

    InspectorApi(
            AdbClient adb,
            CollectorManager collector,
            AgentDeployer agents,
            String defaultPackage,
            InspectionSnapshot initialSnapshot) {
        this(adb, collector, agents, defaultPackage);
        this.current.set(initialSnapshot);
        this.status.set(initialSnapshot == null ? "idle" : "ready");
    }

    @Override
    public String session() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", status.get());
        response.put("defaultPackage", defaultPackage);
        response.put("projectPackages", projectPackages);
        response.put("snapshot", current.get());
        return GSON.toJson(response);
    }

    @Override
    public String devices() {
        try {
            var response = this.devices.list().stream()
                    .map(device -> new DeviceResponse(
                            device.serial(),
                            device.state(),
                            device.model(),
                            device.product(),
                            device.device(),
                            device.ready()))
                    .toList();
            return GSON.toJson(response);
        } catch (Exception error) {
            return error(error);
        }
    }

    @Override
    public String processes(Map<String, String> query) {
        String serial = query.get("serial");
        if (serial == null || serial.isBlank()) return "[]";
        try {
            return GSON.toJson(processes.listForUi(serial, projectPackages));
        } catch (Exception error) {
            return error(error);
        }
    }

    @Override
    public String catalog(Map<String, String> query) {
        String serial = query.get("serial");
        if (serial == null || serial.isBlank()) return "[]";
        String scope = query.getOrDefault("scope", "");
        try {
            List<Map<String, Object>> items = new ArrayList<>();
            if ("process".equalsIgnoreCase(scope)) {
                for (ProcessInfo process : processes.listForUi(serial, projectPackages)) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("type", "process");
                    item.put("id", Integer.toString(process.pid()));
                    item.put("pid", process.pid());
                    item.put("packageName", process.packageName());
                    item.put("processName", process.processName());
                    item.put("label", process.processName());
                    items.add(item);
                }
            } else if ("window".equalsIgnoreCase(scope)) {
                items.addAll(listWindows(serial));
            } else if ("activity".equalsIgnoreCase(scope)) {
                items.addAll(listActivities(serial));
            }
            return GSON.toJson(items);
        } catch (Exception error) {
            return error(error);
        }
    }

    private List<Map<String, Object>> listWindows(String serial) throws Exception {
        String output = shell(serial, "dumpsys", "window", "windows");
        List<Map<String, Object>> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String line : output.split("\n")) {
            Matcher matcher = WINDOW_PATTERN.matcher(line);
            if (matcher.find()) {
                String content = matcher.group(1).trim();
                if (content.isEmpty()) continue;
                String[] parts = content.split("\\s+");
                String key = parts[parts.length - 1];
                if (!seen.add(key)) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("type", "window");
                item.put("id", key);
                item.put("label", content);
                item.put("title", content);
                item.put("processName", key);
                items.add(item);
            }
        }
        return items;
    }

    private List<Map<String, Object>> listActivities(String serial) throws Exception {
        String output = shell(serial, "dumpsys", "activity", "activities");
        List<Map<String, Object>> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String line : output.split("\n")) {
            Matcher matcher = ACTIVITY_PATTERN.matcher(line);
            if (matcher.find()) {
                String name = matcher.group(1);
                if (!seen.add(name)) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("type", "activity");
                item.put("id", name);
                item.put("label", name);
                items.add(item);
            }
        }
        return items;
    }

    private String shell(String serial, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("-s");
        command.add(serial);
        command.add("shell");
        command.addAll(List.of(args));
        return adb.run(command, Duration.ofSeconds(15))
                .requireSuccess("Running shell command").stdout();
    }

    @Override
    public synchronized String capture(String body, Map<String, String> query) {
        status.set("capturing");
        try {
            CaptureRequest request = GSON.fromJson(body, CaptureRequest.class);
            if (request == null || request.serial == null || request.serial.isBlank()) {
                throw new IllegalArgumentException("Select an Android device before capture.");
            }
            String scopeParam = request.scope;
            CaptureScope scope;
            if ("global".equalsIgnoreCase(scopeParam)) {
                scope = CaptureScope.GLOBAL;
            } else if ("process".equalsIgnoreCase(scopeParam)) {
                scope = CaptureScope.PROCESS_WINDOWS;
            } else {
                scope = CaptureScope.TARGET_PROCESS;
            }
            boolean collectorInstalled = collector.install(request.serial);

            List<RunningProcess> running = processes.list(request.serial);
            boolean needsProcess = scope == CaptureScope.TARGET_PROCESS
                    || scope == CaptureScope.PROCESS_WINDOWS;
            RunningProcess target = needsProcess ? findTarget(running, request) : null;
            String packageName = target == null ? null : target.packageName();

            // 提前判定增强采集是否可用，以便 semantic/screenshot/enhanced 三路并行。
            // TARGET_PROCESS 与 PROCESS_WINDOWS（进程 Window）都优先走 JVMTI 真实 View 树：
            // 它通过 WindowManagerGlobal 枚举进程内全部 Window（含 addView 添加的自定义 View），
            // 并把来源标记为 Real View；不可调试或附加失败时再降级为语义采集。
            boolean runEnhanced = scope == CaptureScope.TARGET_PROCESS
                    || scope == CaptureScope.PROCESS_WINDOWS;
            boolean debuggableTarget = false;
            String abi = null;
            if (runEnhanced && target != null) {
                debuggableTarget = processes.isDebuggable(request.serial, packageName);
                if (debuggableTarget) {
                    abi = deviceAbi(request.serial);
                }
            }

            // 先并行抓语义 + 截图，再按需跑 JVMTI：避免三路同时抢占 adb / SurfaceFlinger
            // / 目标 App 主线程，防止逐帧动画在抓取期间丢帧或局部区域显示不出来。
            Future<InspectionSnapshot> semanticFuture = captureExecutor.submit(
                    () -> semantic.capture(request.serial, scope, packageName));
            Future<Screenshot> screenshotFuture = captureExecutor.submit(
                    () -> screenshots.capture(request.serial));
            final RunningProcess enhancedTarget = target;
            final String enhancedAbi = abi;

            InspectionSnapshot semanticSnapshot;
            Screenshot screenshot;
            try {
                semanticSnapshot = semanticFuture.get(35, TimeUnit.SECONDS);
                screenshot = screenshotFuture.get(25, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof IOException io) throw io;
                if (e.getCause() instanceof InterruptedException ie) throw ie;
                throw new IOException("Parallel capture failed.", e.getCause());
            }

            InspectionSnapshot enhanced = null;
            Diagnostic degradation = runEnhanced && target != null && !debuggableTarget
                    ? new Diagnostic("WARN", "enhanced_unavailable",
                            "Target process is not debuggable; showing public semantic nodes.")
                    : null;
            if (debuggableTarget && enhancedAbi != null && !enhancedAbi.isBlank()) {
                try {
                    Future<InspectionSnapshot> enhancedFuture = captureExecutor.submit(
                            () -> agents.capture(request.serial, enhancedTarget, enhancedAbi));
                    enhanced = enhancedFuture.get(75, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    degradation = new Diagnostic("WARN", "enhanced_unavailable",
                            cause == null || cause.getMessage() == null
                                    ? "Enhanced capture failed."
                                    : cause.getMessage());
                }
            }
            List<ProcessInfo> processInfo = new ArrayList<>();
            for (RunningProcess value : running) {
                boolean selected = target != null && value.pid() == target.pid();
                processInfo.add(new ProcessInfo(
                        value.pid(), value.user(), value.packageName(), value.processName(),
                        selected && debuggableTarget));
            }
            Diagnostic installWarning = collectorInstalled
                    ? new Diagnostic("WARN", "collector_updated",
                            "Collector APK 已更新。如视图树为空，请确认设备上的 UI Inspector Collector 无障碍服务已开启。")
                    : null;
            InspectionSnapshot merged = SnapshotMerger.merge(
                    semanticSnapshot, enhanced, screenshot, processInfo,
                    Stream.of(degradation, installWarning).filter(Objects::nonNull).toList());
            // ??/??????????JVMTI ????????????????????????
            // ? Agent/MCP ????????????????????
            boolean targetScope = scope == CaptureScope.TARGET_PROCESS || scope == CaptureScope.PROCESS_WINDOWS;
            if (targetScope && isEmptySnapshot(merged)) {
                try {
                    InspectionSnapshot global = semantic.capture(request.serial, CaptureScope.GLOBAL, null);
                    if (!isEmptySnapshot(global)) {
                        merged = SnapshotMerger.merge(
                                global, enhanced, screenshot, processInfo,
                                Stream.of(degradation, new Diagnostic("WARN", "global_fallback",
                                        "Process enhanced/semantic capture was empty; using global accessibility snapshot."))
                                        .filter(Objects::nonNull).toList());
                    } else {
                        merged = global;
                    }
                } catch (Exception ignored) {
                    // global fallback is best-effort; keep the (possibly empty) original result
                }
            }
            InspectionSnapshot filtered = filterSnapshot(merged, request.windowIds, request.activities);
            current.set(filtered);
            status.set("ready");
            return SnapshotJson.encode(filtered);
        } catch (Exception error) {
            status.set("error");
            return error(error);
        }
    }

    private static boolean isEmptySnapshot(InspectionSnapshot snapshot) {
        return snapshot == null || (snapshot.windows() == null || snapshot.windows().isEmpty())
                && (snapshot.nodes() == null || snapshot.nodes().isEmpty());
    }

    private static InspectionSnapshot filterSnapshot(InspectionSnapshot snapshot,
            List<String> windowIds, List<String> activities) {
        if (isEmptySelection(windowIds) && isEmptySelection(activities)) {
            return snapshot;
        }
        List<WindowSnapshot> keptWindows = new ArrayList<>();
        Set<String> keptWindowIds = new HashSet<>();
        for (WindowSnapshot window : snapshot.windows()) {
            boolean keep;
            if (!isEmptySelection(windowIds)) {
                keep = matchesAny(window, windowIds);
            } else {
                keep = matchesAny(window, activities);
            }
            if (keep) {
                keptWindows.add(window);
                if (window.id() != null) keptWindowIds.add(window.id());
            }
        }
        Map<String, UiNode> keptNodes = new LinkedHashMap<>();
        for (Map.Entry<String, UiNode> entry : snapshot.nodes().entrySet()) {
            UiNode node = entry.getValue();
            if (node.windowId() != null && keptWindowIds.contains(node.windowId())) {
                keptNodes.put(entry.getKey(), node);
                keepAncestors(snapshot.nodes(), node, keptNodes);
            }
        }
        return new InspectionSnapshot(
                snapshot.schemaVersion(),
                snapshot.capturedAtEpochMs(),
                snapshot.deviceSerial(),
                snapshot.scope(),
                snapshot.targetPackage(),
                snapshot.targetProcess(),
                snapshot.processes(),
                keptWindows,
                keptNodes,
                snapshot.diagnostics(),
                snapshot.screenshot());
    }

    private static boolean isEmptySelection(List<String> selection) {
        if (selection == null || selection.isEmpty()) return true;
        for (String value : selection) {
            if (value != null && !value.isBlank() && !"__all__".equals(value)) return false;
        }
        return true;
    }

    private static boolean matchesAny(WindowSnapshot window, List<String> selection) {
        if (isEmptySelection(selection)) return true;
        for (String query : selection) {
            if (query == null || query.isBlank() || "__all__".equals(query)) continue;
            if (matches(window.id(), query)) return true;
            if (matches(window.title(), query)) return true;
            if (matches(window.packageName(), query)) return true;
            if (matches(window.processName(), query)) return true;
        }
        return false;
    }

    private static boolean matches(String value, String query) {
        if (value == null) return false;
        return value.equals(query) || value.contains(query) || query.contains(value);
    }

    private static void keepAncestors(Map<String, UiNode> nodes, UiNode node,
            Map<String, UiNode> output) {
        String parentId = node.parentId();
        int guard = 0;
        while (parentId != null && !parentId.isBlank()
                && !output.containsKey(parentId) && guard++ < 64) {
            UiNode parent = nodes.get(parentId);
            if (parent == null) break;
            output.put(parentId, parent);
            parentId = parent.parentId();
        }
    }

    @Override
    public synchronized String mutate(String body, Map<String, String> query) {
        status.set("mutating");
        try {
            MutationRequest request = GSON.fromJson(body, MutationRequest.class);
            validateRequest(request);
            InspectionSnapshot snapshot = current.get();
            if (snapshot == null) throw apiFailure("stale_target", "Capture the target before editing.");
            if (snapshot.scope() != CaptureScope.TARGET_PROCESS) {
                throw apiFailure("target_read_only", "Global capture is read-only.");
            }
            if (!request.serial.equals(snapshot.deviceSerial())
                    || !request.packageName.equals(snapshot.targetPackage())
                    || !request.processName.equals(snapshot.targetProcess())) {
                throw apiFailure("stale_target", "The current capture belongs to a different target.");
            }

            MutationTarget target = resolveMutationTarget(snapshot, request.targetId);
            if (target.source() != CaptureSource.JVMTI) {
                throw apiFailure("target_read_only", "Only Real View properties can be edited.");
            }
            if (!Objects.equals(request.expectedClassName, target.className())
                    || !Objects.equals(request.expectedResourceId, target.resourceId())) {
                throw apiFailure("stale_target", "The selected View identity has changed.");
            }
            PropertyValue property = findProperty(target.groups(), request.propertyName);
            if (property == null || property.editor() == null) {
                throw apiFailure("property_read_only", property != null && property.readOnlyReason() != null
                        ? property.readOnlyReason() : "This property is read-only.");
            }

            List<RunningProcess> running = processes.list(request.serial);
            RunningProcess process = running.stream()
                    .filter(value -> value.pid() == request.pid)
                    .filter(value -> request.packageName.equals(value.packageName()))
                    .filter(value -> request.processName.equals(value.processName()))
                    .findFirst()
                    .orElseThrow(() -> apiFailure("stale_target", "The selected process is no longer running."));
            if (!processes.isDebuggable(request.serial, process.packageName())) {
                throw apiFailure("target_not_debuggable", "The selected process is not debuggable.");
            }

            var editor = property.editor();
            MutationCommand command = new MutationCommand(
                    MutationCommand.CURRENT_SCHEMA_VERSION,
                    request.targetId,
                    target.className(),
                    target.resourceId(),
                    request.propertyName,
                    editor.adapter(),
                    editor.target(),
                    editor.valueType(),
                    request.value);
            MutationResult result = agents.mutate(
                    request.serial, process, deviceAbi(request.serial), command);
            if (!result.success()) {
                status.set("ready");
                return MutationJson.encodeResult(result);
            }
            if (result.snapshot() == null) {
                throw apiFailure("mutation_failed", "The target did not return a refreshed snapshot.");
            }

            var screenshot = screenshots.capture(request.serial);
            List<ProcessInfo> processInfo = running.stream()
                    .map(value -> new ProcessInfo(
                            value.pid(), value.user(), value.packageName(), value.processName(),
                            value.pid() == process.pid()))
                    .toList();
            InspectionSnapshot fresh = result.snapshot();
            InspectionSnapshot merged = new InspectionSnapshot(
                    InspectionSnapshot.CURRENT_SCHEMA_VERSION,
                    fresh.capturedAtEpochMs(),
                    request.serial,
                    CaptureScope.TARGET_PROCESS,
                    request.packageName,
                    request.processName,
                    processInfo,
                    fresh.windows(),
                    fresh.nodes(),
                    fresh.diagnostics(),
                    screenshot);
            current.set(merged);
            status.set("ready");
            return MutationJson.encodeResult(new MutationResult(
                    MutationResult.CURRENT_SCHEMA_VERSION,
                    true,
                    result.code(),
                    result.message(),
                    result.previousValue(),
                    result.appliedValue(),
                    merged));
        } catch (ApiFailure error) {
            status.set("ready");
            return mutationError(error.code, error.getMessage());
        } catch (Exception error) {
            status.set("error");
            return mutationError("mutation_failed", safeMessage(error));
        }
    }

    @Override
    public synchronized String highlight(String body, Map<String, String> query) {
        try {
            HighlightRequest request = GSON.fromJson(body, HighlightRequest.class);
            if (request == null) return "{\"ok\":true}";
            InspectionSnapshot snapshot = current.get();
            if (snapshot == null) return "{\"ok\":true}";

            String nodeId = request.nodeId == null ? "" : request.nodeId;
            Rect bounds = null;
            if (!nodeId.isEmpty()) {
                var node = snapshot.nodes().get(nodeId);
                if (node != null) bounds = node.visibleBounds() != null ? node.visibleBounds() : node.bounds();
                // Try JVMTI path first if this is a Real View node.
                if (node != null && node.source() == CaptureSource.JVMTI
                        && snapshot.scope() == CaptureScope.TARGET_PROCESS) {
                    List<RunningProcess> running = processes.list(snapshot.deviceSerial());
                    RunningProcess process = running.stream()
                            .filter(value -> snapshot.targetPackage().equals(value.packageName()))
                            .filter(value -> snapshot.targetProcess().equals(value.processName()))
                            .findFirst()
                            .orElse(null);
                    if (process != null && processes.isDebuggable(snapshot.deviceSerial(), process.packageName())) {
                        try {
                            agents.highlight(snapshot.deviceSerial(), process,
                                    deviceAbi(snapshot.deviceSerial()), nodeId);
                            return "{\"ok\":true}";
                        } catch (Exception ignored) {
                            // Fall through to semantic path.
                        }
                    }
                }
            } else {
                // nodeId is empty — clear JVMTI highlight.
                if (snapshot.scope() == CaptureScope.TARGET_PROCESS) {
                    List<RunningProcess> running = processes.list(snapshot.deviceSerial());
                    RunningProcess process = running.stream()
                            .filter(value -> snapshot.targetPackage().equals(value.packageName()))
                            .filter(value -> snapshot.targetProcess().equals(value.processName()))
                            .findFirst()
                            .orElse(null);
                    if (process != null && processes.isDebuggable(snapshot.deviceSerial(), process.packageName())) {
                        try {
                            agents.highlight(snapshot.deviceSerial(), process,
                                    deviceAbi(snapshot.deviceSerial()), "");
                        } catch (Exception ignored) {
                            // Highlight clear is non-critical.
                        }
                    }
                }
            }
            // Semantic path: show overlay or clear.
            if (snapshot.deviceSerial() != null) {
                semantic.highlight(snapshot.deviceSerial(), bounds);
            }
            return "{\"ok\":true}";
        } catch (Exception error) {
            return "{\"ok\":false,\"error\":\"" + safeMessage(error) + "\"}";
        }
    }

    @Override
    public synchronized String jvmtiDiag(String body, Map<String, String> query) {
        try {
            JvmtiDiagRequest request = GSON.fromJson(body, JvmtiDiagRequest.class);
            if (request == null || request.serial == null || request.serial.isBlank()) {
                throw new IllegalArgumentException("Select an Android device before JVMTI diagnosis.");
            }
            List<RunningProcess> running = processes.list(request.serial);
            RunningProcess target = findTarget(running, request.packageName, request.processName, request.pid);
            boolean debuggable = processes.isDebuggable(request.serial, target.packageName());
            String abi = deviceAbi(request.serial);
            var diagnostic = agents.diagnoseJvmti(request.serial, target, abi);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("ok", diagnostic.ok());
            response.put("serial", request.serial);
            response.put("pid", target.pid());
            response.put("packageName", target.packageName());
            response.put("processName", target.processName());
            response.put("debuggable", debuggable);
            response.put("abi", abi);
            response.put("status", diagnostic.status());
            response.put("stage", diagnostic.stage());
            response.put("snapshotWritten", diagnostic.snapshotWritten());
            response.put("steps", diagnostic.steps());
            response.put("logcat", diagnostic.logcat());
            return GSON.toJson(response);
        } catch (Exception error) {
            status.set("error");
            return error(error);
        }
    }


    @Override
    public synchronized String debugRestart(String body, Map<String, String> query) {
        try {
            DebugRestartRequest request = GSON.fromJson(body, DebugRestartRequest.class);
            if (request == null || request.serial == null || request.serial.isBlank()) {
                throw new IllegalArgumentException("Select an Android device before restarting for JVMTI.");
            }
            require(request.packageName, "Target package is missing.");
            List<RunningProcess> running = processes.list(request.serial);
            RunningProcess target = findTarget(running, request.packageName, request.processName, request.pid);
            String packageName = target.packageName();
            String processName = target.processName();
            int oldPid = target.pid();

            // 标记为 debug-app（不带 -w，避免等待调试器挂起），随后强停并按 launcher 重启。
            // 这样进程会以 debuggable runtime 启动，`cmd activity attach-agent` 才能真正执行 Agent_OnAttach。
            adb.run(List.of("-s", request.serial, "shell", "am", "set-debug-app", packageName),
                    Duration.ofSeconds(15))
                    .requireSuccess("Marking package debuggable");
            adb.run(List.of("-s", request.serial, "shell", "am", "force-stop", packageName),
                    Duration.ofSeconds(15))
                    .requireSuccess("Stopping target package");
            adb.run(List.of("-s", request.serial, "shell", "monkey",
                            "-p", packageName, "-c", "android.intent.category.LAUNCHER", "1"),
                    Duration.ofSeconds(20))
                    .requireSuccess("Launching target package");

            RunningProcess restarted = waitForRestartedProcess(
                    request.serial, packageName, processName, oldPid);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("ok", true);
            response.put("serial", request.serial);
            response.put("pid", restarted.pid());
            response.put("packageName", restarted.packageName());
            response.put("processName", restarted.processName());
            response.put("debuggable", true);
            response.put("restarted", true);
            response.put("message", "Target restarted with a debuggable runtime; JVMTI agent can now attach.");
            return GSON.toJson(response);
        } catch (Exception error) {
            status.set("error");
            return error(error);
        }
    }

    private RunningProcess waitForRestartedProcess(String serial, String packageName,
            String processName, int oldPid) throws IOException, InterruptedException {
        String wantedProcess = processName == null || processName.isBlank() ? packageName : processName;
        long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
        RunningProcess exact = null;
        RunningProcess fallback = null;
        while (System.nanoTime() < deadline) {
            for (RunningProcess candidate : processes.list(serial)) {
                if (!candidate.packageName().equals(packageName)) continue;
                if (candidate.pid() == oldPid) continue;
                if (fallback == null && candidate.processName().equals(packageName)) fallback = candidate;
                if (wantedProcess.equals(candidate.processName())) {
                    exact = candidate;
                    break;
                }
            }
            if (exact != null) break;
            Thread.sleep(500);
        }
        RunningProcess restarted = exact != null ? exact : fallback;
        if (restarted == null) {
            throw new IllegalArgumentException("Timed out waiting for the restarted process: " + wantedProcess);
        }
        return restarted;
    }

    private static void validateRequest(MutationRequest request) {
        if (request == null) throw apiFailure("invalid_request", "Mutation request is missing.");
        require(request.serial, "Select an Android device before editing.");
        if (request.pid <= 0) throw apiFailure("invalid_request", "Select a running process before editing.");
        require(request.packageName, "Target package is missing.");
        require(request.processName, "Target process is missing.");
        require(request.targetId, "Selected View is missing.");
        require(request.expectedClassName, "Selected View class is missing.");
        require(request.propertyName, "Property name is missing.");
        if (request.value == null) throw apiFailure("invalid_request", "Property value is missing.");
    }

    private static MutationTarget resolveMutationTarget(InspectionSnapshot snapshot, String targetId) {
        UiNode node = snapshot.nodes().get(targetId);
        if (node != null) {
            return new MutationTarget(
                    node.source(), node.className(), node.resourceId(), node.propertyGroups());
        }
        WindowSnapshot window = snapshot.windows().stream()
                .filter(value -> value.id().equals(targetId))
                .findFirst()
                .orElseThrow(() -> apiFailure("stale_target", "The selected target is no longer present."));
        UiNode root = window.rootNodeId() == null ? null : snapshot.nodes().get(window.rootNodeId());
        if (root == null) throw apiFailure("stale_target", "The selected Window root is no longer present.");
        return new MutationTarget(
                window.source(), root.className(), root.resourceId(), window.propertyGroups());
    }

    private static PropertyValue findProperty(List<PropertyGroup> groups, String propertyName) {
        for (PropertyGroup group : groups) {
            PropertyValue value = group.properties().get(propertyName);
            if (value != null) return value;
        }
        return null;
    }

    private static void require(String value, String message) {
        if (value == null || value.isBlank()) throw apiFailure("invalid_request", message);
    }

    private static String mutationError(String code, String message) {
        return MutationJson.encodeResult(new MutationResult(
                MutationResult.CURRENT_SCHEMA_VERSION,
                false,
                code,
                message,
                null,
                null,
                null));
    }

    private static String safeMessage(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static ApiFailure apiFailure(String code, String message) {
        return new ApiFailure(code, message);
    }

    private RunningProcess findTarget(List<RunningProcess> running, CaptureRequest request) {
        return findTarget(running, request.packageName, request.processName, request.pid);
    }

    private RunningProcess findTarget(List<RunningProcess> running, String packageName, String processName, int pid) {
        if (pid > 0) {
            return running.stream().filter(value -> value.pid() == pid).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Selected process is no longer running."));
        }
        String wanted = processName != null ? processName
                : packageName != null ? packageName : defaultPackage;
        if (wanted == null) throw new IllegalArgumentException("Select a target process.");
        final String wantedFinal = wanted;
        return running.stream()
                .filter(value -> wantedFinal.equals(value.processName()) || wantedFinal.equals(value.packageName()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Target process is not running: " + wantedFinal));
    }

    private String deviceAbi(String serial) throws Exception {
        return adb.run(List.of("-s", serial, "shell", "getprop", "ro.product.cpu.abi"), Duration.ofSeconds(10))
                .requireSuccess("Reading device ABI").stdout().strip();
    }

    private static String error(Exception error) {
        JsonObject object = new JsonObject();
        object.addProperty("error", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        return GSON.toJson(object);
    }

    private static final class HighlightRequest {
        String nodeId;
    }

    private static final class CaptureRequest {
        String serial;
        String scope;
        String packageName;
        String processName;
        int pid;
        List<String> windowIds;
        List<String> activities;
    }

    private static final class JvmtiDiagRequest {
        String serial;
        int pid;
        String packageName;
        String processName;
    }


    private static final class DebugRestartRequest {
        String serial;
        int pid;
        String packageName;
        String processName;
    }

    private static final class MutationRequest {
        String serial;
        int pid;
        String packageName;
        String processName;
        String targetId;
        String expectedClassName;
        String expectedResourceId;
        String propertyName;
        String value;
    }

    private record MutationTarget(
            CaptureSource source,
            String className,
            String resourceId,
            List<PropertyGroup> groups) {}

    private static final class ApiFailure extends RuntimeException {
        private final String code;

        private ApiFailure(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    private record DeviceResponse(
            String serial,
            String state,
            String model,
            String product,
            String device,
            boolean ready) {}
}
