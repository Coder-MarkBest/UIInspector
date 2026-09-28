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
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
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

            // 提前判定增强采集是否可用，以便 semantic/screenshot/enhanced 三路并行
            // PROCESS_WINDOWS 是语义按包名聚合，不启用 JVMTI 增强采集
            boolean runEnhanced = scope == CaptureScope.TARGET_PROCESS;
            boolean debuggableTarget = false;
            String abi = null;
            if (runEnhanced && target != null) {
                debuggableTarget = processes.isDebuggable(request.serial, packageName);
                if (debuggableTarget) {
                    abi = deviceAbi(request.serial);
                }
            }

            // 并行采集 semantic、screenshot、enhanced（互不依赖）
            Future<InspectionSnapshot> semanticFuture = captureExecutor.submit(
                    () -> semantic.capture(request.serial, scope, packageName));
            Future<Screenshot> screenshotFuture = captureExecutor.submit(
                    () -> screenshots.capture(request.serial));
            Future<InspectionSnapshot> enhancedFuture = null;
            if (debuggableTarget && abi != null && !abi.isBlank()) {
                enhancedFuture = captureExecutor.submit(
                        () -> agents.capture(request.serial, target, abi));
            }

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
            if (enhancedFuture != null) {
                try {
                    enhanced = enhancedFuture.get(50, TimeUnit.SECONDS);
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
            current.set(merged);
            status.set("ready");
            return SnapshotJson.encode(merged);
        } catch (Exception error) {
            status.set("error");
            return error(error);
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
        if (request.pid > 0) {
            return running.stream().filter(value -> value.pid() == request.pid).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Selected process is no longer running."));
        }
        String wanted = request.processName != null ? request.processName
                : request.packageName != null ? request.packageName : defaultPackage;
        if (wanted == null) throw new IllegalArgumentException("Select a target process.");
        return running.stream()
                .filter(value -> wanted.equals(value.processName()) || wanted.equals(value.packageName()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Target process is not running: " + wanted));
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
