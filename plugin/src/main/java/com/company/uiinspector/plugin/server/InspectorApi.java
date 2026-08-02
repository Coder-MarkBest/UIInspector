package com.company.uiinspector.plugin.server;

import com.company.uiinspector.plugin.adb.AdbClient;
import com.company.uiinspector.plugin.adb.CollectorManager;
import com.company.uiinspector.plugin.adb.DeviceCatalog;
import com.company.uiinspector.plugin.adb.ProcessCatalog;
import com.company.uiinspector.plugin.adb.RunningProcess;
import com.company.uiinspector.plugin.capture.AgentDeployer;
import com.company.uiinspector.plugin.capture.EnhancedCaptureUnavailableException;
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
import com.company.uiinspector.protocol.SnapshotJson;
import com.company.uiinspector.protocol.UiNode;
import com.company.uiinspector.protocol.WindowSnapshot;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

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
            return GSON.toJson(processes.list(serial));
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
            CaptureScope scope = "global".equalsIgnoreCase(request.scope)
                    ? CaptureScope.GLOBAL : CaptureScope.TARGET_PROCESS;
            collector.install(request.serial);

            List<RunningProcess> running = processes.list(request.serial);
            RunningProcess target = scope == CaptureScope.TARGET_PROCESS ? findTarget(running, request) : null;
            String packageName = target == null ? null : target.packageName();
            InspectionSnapshot semanticSnapshot = semantic.capture(request.serial, scope, packageName);
            var screenshot = screenshots.capture(request.serial);
            List<ProcessInfo> processInfo = new ArrayList<>();
            InspectionSnapshot enhanced = null;
            Diagnostic degradation = null;
            for (RunningProcess value : running) {
                boolean selected = target != null && value.pid() == target.pid();
                boolean debuggable = selected && processes.isDebuggable(request.serial, value.packageName());
                processInfo.add(new ProcessInfo(
                        value.pid(), value.user(), value.packageName(), value.processName(), debuggable));
                if (selected) {
                    if (debuggable) {
                        try {
                            enhanced = agents.capture(request.serial, target, deviceAbi(request.serial));
                        } catch (EnhancedCaptureUnavailableException error) {
                            degradation = new Diagnostic("WARN", "enhanced_unavailable", error.getMessage());
                        }
                    } else {
                        degradation = new Diagnostic(
                                "WARN", "enhanced_unavailable",
                                "Target process is not debuggable; showing public semantic nodes.");
                    }
                }
            }
            InspectionSnapshot merged = SnapshotMerger.merge(
                    semanticSnapshot, enhanced, screenshot, processInfo, degradation);
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
