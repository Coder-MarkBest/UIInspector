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
import com.company.uiinspector.protocol.Diagnostic;
import com.company.uiinspector.protocol.InspectionSnapshot;
import com.company.uiinspector.protocol.ProcessInfo;
import com.company.uiinspector.protocol.SnapshotJson;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final AtomicReference<InspectionSnapshot> current = new AtomicReference<>();
    private final AtomicReference<String> status = new AtomicReference<>("idle");

    public InspectorApi(
            AdbClient adb,
            CollectorManager collector,
            AgentDeployer agents,
            String defaultPackage) {
        this.adb = adb;
        this.devices = new DeviceCatalog(adb);
        this.processes = new ProcessCatalog(adb);
        this.collector = collector;
        this.semantic = new SemanticCapture(adb);
        this.screenshots = new ScreenshotCapture(adb);
        this.agents = agents;
        this.defaultPackage = defaultPackage;
    }

    @Override
    public String session() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", status.get());
        response.put("defaultPackage", defaultPackage);
        response.put("snapshot", current.get());
        return GSON.toJson(response);
    }

    @Override
    public String devices() {
        try {
            return GSON.toJson(this.devices.list());
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
}
