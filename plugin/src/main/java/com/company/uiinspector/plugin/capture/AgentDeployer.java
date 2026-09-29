package com.company.uiinspector.plugin.capture;

import com.company.uiinspector.plugin.adb.AdbClient;
import com.company.uiinspector.plugin.adb.RunningProcess;
import com.company.uiinspector.protocol.InspectionSnapshot;
import com.company.uiinspector.protocol.MutationCommand;
import com.company.uiinspector.protocol.MutationJson;
import com.company.uiinspector.protocol.MutationResult;
import com.company.uiinspector.protocol.SnapshotJson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class AgentDeployer {
    private static final Logger LOG = Logger.getLogger(AgentDeployer.class.getName());
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration ATTACH_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration CAPTURE_TIMEOUT = Duration.ofSeconds(60);

    private static final ExecutorService CLEANUP = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "uiinspector-agent-cleanup");
        thread.setDaemon(true);
        return thread;
    });

    private final AdbClient adb;
    private final AgentPayloads payloads;
    private final Supplier<String> captureIds;

    public AgentDeployer(AdbClient adb, AgentPayloads payloads, Supplier<String> captureIds) {
        this.adb = adb;
        this.payloads = payloads;
        this.captureIds = captureIds;
    }

    // ── private directory resolution ──────────────────────────────────────

    /**
     * Resolves the target app's private data directory. Tries {@code run-as}
     * first; if the device runs ADB as root and run-as is unavailable (common
     * for system-installed packages on automotive head units), falls back to
     * direct path construction.
     */
    private AppDir resolveAppDir(String serial, String packageName)
            throws IOException, InterruptedException, EnhancedCaptureUnavailableException {
        // Try run-as (works for user-installed debuggable apps)
        var pwdResult = adb.run(withSerial(serial,
                List.of("shell", "run-as", packageName, "pwd")), COMMAND_TIMEOUT);
        if (pwdResult.exitCode() == 0) {
            String path = pwdResult.stdout().strip();
            if (!path.isBlank()) {
                LOG.fine("run-as pwd succeeded: " + path);
                return new AppDir(path, true);
            }
        } else {
            LOG.fine("run-as pwd failed (exit " + pwdResult.exitCode() + "): "
                    + pwdResult.stderr().strip());
        }
        // Fallback: construct path if running as root
        if (isRootAdb(serial)) {
            String path = "/data/data/" + packageName;
            LOG.info("Using root-direct path: " + path);
            return new AppDir(path, false);
        }
        throw new EnhancedCaptureUnavailableException(
                "run-as is unavailable and ADB is not root; "
                        + "cannot access the target app's private directory.");
    }

    private boolean isRootAdb(String serial) throws IOException, InterruptedException {
        try {
            String uid = adbRun(serial, List.of("shell", "id", "-u"), COMMAND_TIMEOUT, null)
                    .stdout().strip();
            return "0".equals(uid);
        } catch (IOException e) {
            return false;
        }
    }

    // ── file operations (run-as or root) ──────────────────────────────────

    private com.company.uiinspector.plugin.adb.CommandResult adbRun(
            String serial, List<String> args, Duration timeout, String operation)
            throws IOException, InterruptedException {
        var result = adb.run(withSerial(serial, args), timeout);
        if (operation != null) result.requireSuccess(operation);
        return result;
    }

    /** Execute a command inside the app's context (via run-as or root). */
    private void appShell(AppDir app, String serial, String packageName,
            List<String> args, String operation)
            throws IOException, InterruptedException {
        if (app.usesRunAs) {
            // run-as <pkg> <cmd> <args...>
            var cmd = new java.util.ArrayList<String>();
            cmd.add("shell");
            cmd.add("run-as");
            cmd.add(packageName);
            cmd.addAll(args);
            adbRun(serial, cmd, COMMAND_TIMEOUT, operation);
        } else {
            // Direct shell with absolute paths.
            // The first argument is always the command name (mkdir, cp, chmod, rm).
            // Subsequent arguments that are relative paths get the private dir prefix.
            var shellArgs = new java.util.ArrayList<String>();
            shellArgs.add("shell");
            shellArgs.add(args.get(0));  // command name
            for (int i = 1; i < args.size(); i++) {
                String arg = args.get(i);
                if (arg.startsWith("-") || arg.startsWith("/") || arg.chars().allMatch(Character::isDigit)) {
                    shellArgs.add(arg);
                } else {
                    shellArgs.add(app.path + "/" + arg);
                }
            }
            adbRun(serial, shellArgs, COMMAND_TIMEOUT, operation);
        }
    }

    /** Read a file from the app's context (via run-as cat or root cat). */
    private String appRead(AppDir app, String serial, String packageName, String relativePath)
            throws IOException, InterruptedException {
        if (app.usesRunAs) {
            return adbRun(serial, List.of("exec-out", "run-as", packageName, "cat", relativePath),
                    COMMAND_TIMEOUT, null).stdout();
        }
        return adbRun(serial, List.of("shell", "cat", app.path + "/" + relativePath),
                COMMAND_TIMEOUT, null).stdout();
    }

    /** Remove files from the app's context. */
    private void appRemove(AppDir app, String serial, String packageName, List<String> relativePaths) {
        try {
            if (app.usesRunAs) {
                var cmd = new java.util.ArrayList<String>();
                cmd.add("shell");
                cmd.add("run-as");
                cmd.add(packageName);
                cmd.add("rm");
                cmd.add("-f");
                cmd.addAll(relativePaths);
                adb.run(withSerial(serial, cmd), COMMAND_TIMEOUT);
            } else {
                var cmd = new java.util.ArrayList<String>();
                cmd.add("shell");
                cmd.add("rm");
                cmd.add("-f");
                for (String p : relativePaths) cmd.add(app.path + "/" + p);
                adb.run(withSerial(serial, cmd), COMMAND_TIMEOUT);
            }
        } catch (Exception ignored) {
            // Cleanup is best effort.
        }
    }

    /** Execute multiple file operations inside the app context with a single adb call. */
    private void appShellScript(AppDir app, String serial, String packageName,
            String script, String operation)
            throws IOException, InterruptedException {
        // adb 会在设备端把 shell 参数重拼成一行；脚本若不引住，含空格的
        // `sh -c <script>` 会被设备 shell 按空格分词，导致 mkdir 等命令参数丢失。
        String quoted = shellQuote(script);
        if (app.usesRunAs) {
            adbRun(serial, List.of("shell", "run-as", packageName, "sh", "-c", quoted),
                    COMMAND_TIMEOUT, operation);
        } else {
            adbRun(serial, List.of("shell", "sh", "-c", quoted),
                    COMMAND_TIMEOUT, operation);
        }
    }

    /** Block until a result file is non-empty, then read it, in a single adb call. */
    private String appReadBlocking(AppDir app, String serial, String packageName,
            String relativePath, Duration timeout)
            throws IOException, InterruptedException {
        String path = app.usesRunAs ? relativePath : app.path + "/" + relativePath;
        String script = "f=\"" + path + "\"; n=0; while [ ! -s \"$f\" ] && [ $n -lt 600 ]; do sleep 0.1; n=$((n+1)); done; cat \"$f\"";
        String quoted = shellQuote(script);
        if (app.usesRunAs) {
            return adbRun(serial, List.of("exec-out", "run-as", packageName, "sh", "-c", quoted),
                    timeout, null).stdout();
        }
        return adbRun(serial, List.of("exec-out", "sh", "-c", quoted),
                timeout, null).stdout();
    }

    // ── capture ───────────────────────────────────────────────────────────

    public InspectionSnapshot capture(String serial, RunningProcess process, String abi)
            throws EnhancedCaptureUnavailableException, InterruptedException {
        Path agent = payloads.agentFor(abi);
        if (agent == null) {
            throw new EnhancedCaptureUnavailableException("No JVMTI agent payload for device ABI " + abi + ".");
        }
        String packageName = process.packageName();
        String captureId = safe(captureIds.get());
        String prefix = "/data/local/tmp/uiinspector-" + process.pid() + "-" + captureId;
        String tempHelper = prefix + "-helper.apk";
        String tempAgent = prefix + "-agent.so";
        AppDir app = null;
        try {
            app = resolveAppDir(serial, packageName);

            push(serial, payloads.helperApk(), tempHelper, "Deploying View helper");
            push(serial, agent, tempAgent, "Deploying JVMTI agent");
            adbRun(serial, List.of("shell", "chmod", "0644", tempHelper, tempAgent),
                    COMMAND_TIMEOUT, "Preparing agent payloads");

            String remoteDir = "code_cache/uiinspector";
            String helperFile = remoteDir + "/helper-" + captureId + ".apk";
            String agentFile = remoteDir + "/libuiinspector_agent_" + captureId + ".so";
            String snapshotFile = remoteDir + "/snapshot-" + captureId + ".json";

            String helperRemote = app.usesRunAs ? helperFile : app.path + "/" + helperFile;
            String agentRemote = app.usesRunAs ? agentFile : app.path + "/" + agentFile;
            String dirArg = app.usesRunAs ? remoteDir : app.path + "/" + remoteDir;
            String prepScript = "mkdir -p " + dirArg
                    + " && cp " + tempHelper + " " + helperRemote
                    + " && cp " + tempAgent + " " + agentRemote
                    + " && chmod 0444 " + helperRemote + " " + agentRemote;
            appShellScript(app, serial, packageName, prepScript, "Preparing capture payloads");

            String remoteAgent = app.path + "/" + agentFile;
            String remoteHelper = app.path + "/" + helperFile;
            String remoteSnapshot = app.path + "/" + snapshotFile;

            adbRun(serial, List.of(
                    "shell", "cmd", "activity", "attach-agent", process.processName(),
                    remoteAgent + "=" + remoteHelper + "," + remoteSnapshot),
                    ATTACH_TIMEOUT, "Attaching JVMTI agent");
            LOG.info("attach-agent succeeded for " + process.processName()
                    + " (pid=" + process.pid() + ", runAs=" + app.usesRunAs + "), polling for snapshot");
            return waitForSnapshot(app, serial, packageName, snapshotFile);
        } catch (IOException error) {
            LOG.log(Level.WARNING, "Enhanced capture failed for " + process.processName()
                    + " (pid=" + process.pid() + "): " + error.getMessage(), error);
            throw new EnhancedCaptureUnavailableException(error.getMessage(), error);
        } finally {
            AppDir cleanupApp = app;
            CLEANUP.submit(() -> cleanupCapture(serial, packageName, captureId, tempHelper, tempAgent, cleanupApp));
        }
    }


    // ── JVMTI diagnosis ─────────────────────────────────────────────

    private static final Duration DIAG_WAIT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Probes whether the JVMTI agent can attach to a specific process and how far
     * the capture pipeline gets. This powers the on-demand JVMTI diagnostic tool
     * used for intermittent enhanced_capture timeouts.
     */
    public JvmtiDiagnosis diagnoseJvmti(String serial, RunningProcess process, String abi)
            throws IOException, InterruptedException, EnhancedCaptureUnavailableException {
        String packageName = process.packageName();
        String captureId = "diag-" + safe(captureIds.get());
        String prefix = "/data/local/tmp/uiinspector-" + process.pid() + "-" + captureId;
        String tempHelper = prefix + "-helper.apk";
        String tempAgent = prefix + "-agent.so";
        Path agent = payloads.agentFor(abi);
        List<DiagStep> steps = new java.util.ArrayList<>();
        steps.add(DiagStep.ok("process", process.processName() + " (pid=" + process.pid()
                + ", package=" + packageName + ")"));
        if (agent == null) {
            steps.add(DiagStep.fail("abi", "No JVMTI agent payload for device ABI " + abi + "."));
            return new JvmtiDiagnosis(false, "NO_PAYLOAD", "abi", steps, false, readAgentLogs(serial));
        }
        steps.add(DiagStep.ok("abi", abi + " -> " + agent.getFileName()));
        String buildInfo = buildInfo(serial);
        if (buildInfo != null && !buildInfo.isBlank()) {
            steps.add(DiagStep.ok("build", buildInfo));
        }
        AppDir app = null;
        try {
            app = resolveAppDir(serial, packageName);
            steps.add(DiagStep.ok("app_dir", app.usesRunAs ? "run-as " + app.path : "root " + app.path));

            push(serial, payloads.helperApk(), tempHelper, "Deploying diagnostic helper");
            push(serial, agent, tempAgent, "Deploying diagnostic agent");
            steps.add(DiagStep.ok("deploy", "helper APK + agent .so pushed to /data/local/tmp"));

            String remoteDir = "code_cache/uiinspector";
            String helperFile = remoteDir + "/helper-" + captureId + ".apk";
            String agentFile = remoteDir + "/libuiinspector_agent_" + captureId + ".so";
            String snapshotFile = remoteDir + "/snapshot-" + captureId + ".json";
            String helperRemote = app.usesRunAs ? helperFile : app.path + "/" + helperFile;
            String agentRemote = app.usesRunAs ? agentFile : app.path + "/" + agentFile;
            String dirArg = app.usesRunAs ? remoteDir : app.path + "/" + remoteDir;
            String prepScript = "mkdir -p " + dirArg + " && cp " + tempHelper + " " + helperRemote
                    + " && cp " + tempAgent + " " + agentRemote
                    + " && chmod 0444 " + helperRemote + " " + agentRemote;
            appShellScript(app, serial, packageName, prepScript, "Preparing diagnostic payloads");
            steps.add(DiagStep.ok("prep", "payloads copied into app-private directory"));

            String remoteAgent = app.path + "/" + agentFile;
            String remoteHelper = app.path + "/" + helperFile;
            String remoteSnapshot = app.path + "/" + snapshotFile;
            var attach = adb.run(withSerial(serial, List.of(
                    "shell", "cmd", "activity", "attach-agent", process.processName(),
                    remoteAgent + "=" + remoteHelper + "," + remoteSnapshot)), ATTACH_TIMEOUT);
            if (attach.exitCode() != 0) {
                steps.add(DiagStep.fail("attach", "attach-agent rc=" + attach.exitCode()
                        + " " + (attach.stderr() == null ? "" : attach.stderr().strip())));
                return new JvmtiDiagnosis(false, "ATTACH_FAILED", "attach", steps,
                        false, readAgentLogs(serial));
            }
            steps.add(DiagStep.ok("attach", "cmd activity attach-agent accepted"));

            // Wait for the agent to crawl through pipeline stages and/or write a snapshot.
            long deadline = System.nanoTime() + DIAG_WAIT_TIMEOUT.toNanos();
            String logs = "";
            boolean snapshotWritten = false;
            while (System.nanoTime() < deadline) {
                String fresh = readAgentLogs(serial);
                if (fresh != null && !fresh.isBlank()) logs = fresh;
                try {
                    String stdout = appRead(app, serial, packageName, snapshotFile);
                    if (stdout != null && !stdout.isBlank()) {
                        snapshotWritten = true;
                        break;
                    }
                } catch (IOException ignored) {
                    // Result file not written yet.
                }
                Thread.sleep(250);
            }

            Stage stage = classifyJvmti(logs, snapshotWritten);
            boolean neverReachedAgent = "none".equals(stage.stage) || "no_attach".equals(stage.stage);
            String systemLogs = neverReachedAgent ? readSystemLogs(serial) : null;
            if (systemLogs != null && !systemLogs.isBlank()) {
                steps.add(DiagStep.ok("system_logs", "已抓取 attach 相关系统 logcat（见下方 logcat 区）。"));
            }
            String diagLogs = combineLogs(logs, systemLogs);
            steps.add(DiagStep.ok("probe", "Furthest agent stage: " + stage.stage
                    + (stage.error == null || stage.error.isBlank() ? "" : " — " + stage.error)));
            return new JvmtiDiagnosis(snapshotWritten || stage.complete,
                    snapshotWritten ? "COMPLETE" : "STUCK_AT_STAGE", stage.stage, steps,
                    snapshotWritten, logTail(diagLogs, 200));
        } catch (IOException error) {
            steps.add(DiagStep.fail("probe", error.getMessage()));
            return new JvmtiDiagnosis(false, "ERROR", "probe", steps, false, readAgentLogs(serial));
        } finally {
            AppDir cleanupApp = app;
            CLEANUP.submit(() -> cleanupCapture(serial, packageName, captureId, tempHelper, tempAgent, cleanupApp));
        }
    }

    /** On-demand JVMTI diagnosis result. */
    public record JvmtiDiagnosis(
            boolean ok,
            String status,
            String stage,
            List<DiagStep> steps,
            boolean snapshotWritten,
            String logcat) {}

    /** One named diagnostic step. */
    public record DiagStep(String name, boolean ok, String detail) {
        static DiagStep ok(String name, String detail) { return new DiagStep(name, true, detail); }
        static DiagStep fail(String name, String detail) { return new DiagStep(name, false, detail); }
    }

    private record Stage(boolean complete, String stage, String error) {}

    /** Maps captured agent logs to the furthest pipeline stage reached. */
    private static Stage classifyJvmti(String logs, boolean snapshotWritten) {
        if (snapshotWritten) return new Stage(true, "snapshot_written", null);
        if (logs == null || logs.isBlank()) {
            return new Stage(false, "none", "No UIInspectorAgent log captured; attach likely never reached the agent.");
        }
        if (logs.contains("Agent_OnAttach called")) {
            if (logs.contains("Bad option count") || logs.contains("Agent_OnAttach returning JNI_ERR")) {
                return new Stage(false, "agent_failed", "Agent started but returned JNI_ERR (see logcat).");
            }
            if (logs.contains("WindowManagerGlobal unavailable")) {
                return new Stage(false, "window_manager_global", "WindowManagerGlobal.getInstance failed by reflection.");
            }
            if (logs.contains("mViews field unavailable")) {
                return new Stage(false, "mviews", "WindowManagerGlobal.mViews reflection failed on this OS.");
            }
            if (logs.contains("collect_window_params failed")) {
                return new Stage(false, "window_params", "Window root params collection failed.");
            }
            if (logs.contains("ViewCaptureBridge loaded")) {
                if (logs.contains("Capture posted to main thread successfully")) {
                    if (logs.contains("captureNow starting")) {
                        return new Stage(false, "capture_running",
                                "Capture is running but did not write the snapshot within the wait window (large tree / slow main thread).");
                    }
                    if (logs.contains("ViewCaptureBridge.capture called")) {
                        return new Stage(false, "capture_posted",
                                "capture() posted to the main thread but the runnable never started — target main thread likely busy or frozen.");
                    }
                    return new Stage(false, "capture_invoked",
                            "capture() was invoked on the attach thread, but no main-thread delivery was observed.");
                }
                return new Stage(false, "helper_loaded",
                        "ViewCaptureBridge loaded but capture() failed or threw on the attach thread.");
            }
            return new Stage(false, "agent_on_attach",
                    "Agent attached but stopped before ViewCaptureBridge (helper load / reflection).");
        }
        return new Stage(false, "no_attach",
                "No Agent_OnAttach log found — JVMTI attach did not reach the agent (not debuggable? ART rejected? wrong pid?).");
    }

    /** Last {@code maxLines} lines of captured logcat. */
    private static String logTail(String logs, int maxLines) {
        if (logs == null || logs.isBlank()) return "";
        String[] lines = logs.split("\\R");
        if (lines.length <= maxLines) return logs.strip();
        StringBuilder tail = new StringBuilder();
        for (int index = lines.length - maxLines; index < lines.length; index++) {
            if (tail.length() > 0) tail.append(System.lineSeparator());
            tail.append(lines[index]);
        }
        return tail.toString();
    }

    private void push(String serial, Path local, String remote, String operation)
            throws IOException, InterruptedException {
        adbRun(serial, List.of("push", local.toString(), remote), COMMAND_TIMEOUT, operation);
    }

    // ── highlight ─────────────────────────────────────────────────────────

    public void highlight(String serial, RunningProcess process, String abi, String nodeId)
            throws EnhancedCaptureUnavailableException, InterruptedException {
        Path agent = payloads.agentFor(abi);
        if (agent == null) {
            throw new EnhancedCaptureUnavailableException("No JVMTI agent payload for device ABI " + abi + ".");
        }
        String packageName = process.packageName();
        String captureId = safe(captureIds.get());
        String prefix = "/data/local/tmp/uiinspector-" + process.pid() + "-" + captureId;
        String tempHelper = prefix + "-helper-hl.apk";
        String tempAgent = prefix + "-agent-hl.so";
        AppDir app = null;
        try {
            app = resolveAppDir(serial, packageName);

            push(serial, payloads.helperApk(), tempHelper, "Deploying highlight helper");
            push(serial, agent, tempAgent, "Deploying highlight agent");
            adbRun(serial, List.of("shell", "chmod", "0644", tempHelper, tempAgent),
                    COMMAND_TIMEOUT, "Preparing highlight payloads");

            String remoteDir = "code_cache/uiinspector";
            String helperFile = remoteDir + "/helper-hl-" + captureId + ".apk";
            String agentFile = remoteDir + "/libuiinspector_agent_hl_" + captureId + ".so";
            String resultFile = remoteDir + "/highlight-result-" + captureId + ".json";

            appShell(app, serial, packageName,
                    List.of("mkdir", "-p", remoteDir), "Creating highlight directory");
            appShell(app, serial, packageName,
                    List.of("cp", tempHelper, helperFile), "Copying highlight helper");
            appShell(app, serial, packageName,
                    List.of("cp", tempAgent, agentFile), "Copying highlight agent");
            appShell(app, serial, packageName,
                    List.of("chmod", "0444", helperFile, agentFile),
                    "Protecting highlight payloads");

            String remoteAgent = app.path + "/" + agentFile;
            String remoteHelper = app.path + "/" + helperFile;
            String remoteResult = app.path + "/" + resultFile;
            String safeNodeId = nodeId == null ? "" : nodeId;

            adbRun(serial, List.of(
                    "shell", "cmd", "activity", "attach-agent", process.processName(),
                    remoteAgent + "=" + remoteHelper + ",highlight_" + safeNodeId + "," + remoteResult),
                    ATTACH_TIMEOUT, "Attaching highlight JVMTI agent");
            LOG.fine("Highlight attach-agent sent for " + process.processName());

            waitForHighlightResult(app, serial, packageName, resultFile);
        } catch (IOException error) {
            LOG.warning("Highlight failed for " + process.processName() + ": " + error.getMessage());
        } finally {
            AppDir cleanupApp = app;
            CLEANUP.submit(() -> cleanupHighlight(serial, captureId, tempHelper, tempAgent, cleanupApp));
        }
    }

    private void waitForHighlightResult(AppDir app, String serial, String packageName, String relativePath)
            throws IOException, InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                String stdout = appRead(app, serial, packageName, relativePath);
                if (stdout != null && stdout.contains("\"ok\":true")) {
                    LOG.fine("Highlight result confirmed");
                    return;
                }
            } catch (IOException ignored) {
                // File not ready yet.
            }
            Thread.sleep(50);
        }
        LOG.fine("Highlight result polling ended (timeout or success)");
    }

    private void cleanupHighlight(String serial, String captureId,
            String tempHelper, String tempAgent, AppDir app) {
        try {
            adb.run(withSerial(serial, List.of("shell", "rm", "-f", tempHelper, tempAgent)),
                    COMMAND_TIMEOUT);
            if (app != null) {
                appRemove(app, serial, null, List.of(
                        "code_cache/uiinspector/helper-hl-" + captureId + ".apk",
                        "code_cache/uiinspector/libuiinspector_agent_hl_" + captureId + ".so",
                        "code_cache/uiinspector/highlight-result-" + captureId + ".json"));
            }
        } catch (Exception ignored) {
            // Cleanup is best effort.
        }
    }

    private InspectionSnapshot waitForSnapshot(AppDir app, String serial, String packageName, String relativePath)
            throws IOException, InterruptedException, EnhancedCaptureUnavailableException {
        // 轮询方式读取结果文件：每条 adb 只执行一次 cat，文件未就绪时短暂休眠后重试。
        // 不使用单条阻塞式脚本（appReadBlocking）：在部分设备/Windows adb 组合下，
        // 嵌入式 `sh -c` 脚本的引号会被二次 shell 解析，导致命令立即空返回，
        // 无法等到 agent 写入快照（设备端实际抓取需数十秒）。
        long deadline = System.nanoTime() + CAPTURE_TIMEOUT.toNanos();
        String lastParseError = null;
        while (System.nanoTime() < deadline) {
            String stdout;
            try {
                stdout = appRead(app, serial, packageName, relativePath);
            } catch (IOException e) {
                // 文件尚未就绪或 cat 失败，等待后重试。
                Thread.sleep(200);
                continue;
            }
            if (stdout != null && !stdout.isBlank()) {
                try {
                    InspectionSnapshot snapshot = SnapshotJson.decode(
                            AdbJsonOutput.payload(stdout, "Target View snapshot"));
                    LOG.info("Snapshot file found and parsed.");
                    return snapshot;
                } catch (IOException | RuntimeException error) {
                    lastParseError = error.getMessage();
                    LOG.fine("Retrying snapshot parse: " + error.getMessage());
                }
            }
            Thread.sleep(200);
        }
        String reason = "Timed out waiting for the target View snapshot.";
        if (lastParseError != null && !lastParseError.isBlank()) {
            reason += System.lineSeparator() + "Last parse failure: " + lastParseError;
        }
        String agentLogs = readAgentLogs(serial);
        if (agentLogs != null && !agentLogs.isBlank()) {
            reason += System.lineSeparator() + "Agent device logcat:"
                    + System.lineSeparator() + agentLogs;
        } else {
            // attach-agent 被接受但 Agent_OnAttach 从未被调用：附加设备构建属性
            // 与近期系统/attach 相关 logcat，帮助定位 jvmti 为何没有真正生效。
            reason += System.lineSeparator()
                    + "attach-agent was accepted but UIInspectorAgent was never invoked "
                    + "(possible causes: runtime not debuggable, user build, or ART/ROM restriction).";
            String build = buildInfo(serial);
            if (build != null && !build.isBlank()) {
                reason += System.lineSeparator() + "Build: " + build;
            }
            String combined = combineLogs(null, readSystemLogs(serial));
            if (combined != null && !combined.isBlank()) {
                reason += System.lineSeparator() + "Recent system/attach logcat:"
                        + System.lineSeparator() + combined;
            }
        }
        throw new EnhancedCaptureUnavailableException(reason);
    }
    /** 收集目标设备 UIInspectorAgent 的最近日志，用于定位增强采集在设备端卡住的位置。 */
    private String readAgentLogs(String serial) {
        try {
            var result = adb.run(withSerial(serial, List.of(
                    "logcat", "-d", "-t", "200", "-s", "UIInspectorAgent")), Duration.ofSeconds(5));
            if (result.exitCode() != 0) return null;
            String logs = result.stdout();
            return logs == null || logs.isBlank() ? null : logs.strip();
        } catch (IOException | InterruptedException error) {
            if (error instanceof InterruptedException interrupted) Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Compact build properties that determine whether dynamic JVMTI attach is permitted. */
    private String buildInfo(String serial) {
        try {
            return "sdk=" + blankTo(prop(serial, "ro.build.version.sdk"), "?")
                    + " release=" + blankTo(prop(serial, "ro.build.version.release"), "?")
                    + " ro.debuggable=" + blankTo(prop(serial, "ro.debuggable"), "?")
                    + " build.type=" + blankTo(prop(serial, "ro.build.type"), "?");
        } catch (Exception ignored) {
            return null;
        }
    }

    private String prop(String serial, String key) throws IOException, InterruptedException {
        var result = adb.run(withSerial(serial, List.of("shell", "getprop", key)), COMMAND_TIMEOUT);
        return result.exitCode() == 0 ? result.stdout().strip() : "";
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /** Read the last chunk of full logcat, keeping lines relevant to JVMTI attach. */
    private String readSystemLogs(String serial) {
        try {
            var result = adb.run(withSerial(serial, List.of(
                    "logcat", "-d", "-t", "600")), Duration.ofSeconds(6));
            if (result.exitCode() != 0) return null;
            String output = result.stdout();
            if (output == null || output.isBlank()) return null;
            StringBuilder hits = new StringBuilder();
            for (String line : output.split("\\R")) {
                String lower = line.toLowerCase();
                if (lower.contains("attach-agent") || lower.contains("attachagent")
                        || lower.contains("art::") || lower.contains("androidruntime")
                        || lower.contains("artd") || lower.contains("jdwp")
                        || lower.contains("debuggable") || lower.contains("system_server")
                        || lower.contains("activitytaskmanager")
                        || lower.contains("libuiinspector") || lower.contains("uiinspectoragent")) {
                    if (hits.length() > 0) hits.append(System.lineSeparator());
                    hits.append(line);
                }
            }
            return hits.length() == 0 ? null : hits.toString();
        } catch (IOException | InterruptedException error) {
            if (error instanceof InterruptedException ie) Thread.currentThread().interrupt();
            return null;
        }
    }

    private static String combineLogs(String agentLogs, String systemLogs) {
        StringBuilder combined = new StringBuilder();
        if (agentLogs != null && !agentLogs.isBlank()) {
            combined.append("--- UIInspectorAgent ---").append(System.lineSeparator()).append(agentLogs);
        }
        if (systemLogs != null && !systemLogs.isBlank()) {
            if (combined.length() > 0) combined.append(System.lineSeparator());
            combined.append("--- system/attach ---").append(System.lineSeparator()).append(systemLogs);
        }
        return combined.toString();
    }

    private void cleanupCapture(String serial, String packageName, String captureId,
            String tempHelper, String tempAgent, AppDir app) {
        try {
            adb.run(withSerial(serial, List.of("shell", "rm", "-f", tempHelper, tempAgent)),
                    COMMAND_TIMEOUT);
            if (app != null) {
                appRemove(app, serial, packageName, List.of(
                        "code_cache/uiinspector/helper-" + captureId + ".apk",
                        "code_cache/uiinspector/libuiinspector_agent_" + captureId + ".so",
                        "code_cache/uiinspector/snapshot-" + captureId + ".json"));
            }
        } catch (Exception ignored) {
            // Cleanup is best effort.
        }
    }

    // ── mutation ──────────────────────────────────────────────────────────

    public MutationResult mutate(
            String serial,
            RunningProcess process,
            String abi,
            MutationCommand mutation)
            throws EnhancedCaptureUnavailableException, InterruptedException {
        Path agent = payloads.agentFor(abi);
        if (agent == null) {
            throw new EnhancedCaptureUnavailableException("No JVMTI agent payload for device ABI " + abi + ".");
        }
        String packageName = process.packageName();
        String captureId = safe(captureIds.get());
        String prefix = "/data/local/tmp/uiinspector-" + process.pid() + "-" + captureId;
        String tempHelper = prefix + "-helper.apk";
        String tempAgent = prefix + "-agent.so";
        String tempCommand = prefix + "-command.json";
        AppDir app = null;
        Path localCommand = null;
        try {
            localCommand = Files.createTempFile("uiinspector-mutation-", ".json");
            Files.writeString(localCommand, MutationJson.encodeCommand(mutation));
            app = resolveAppDir(serial, packageName);

            push(serial, payloads.helperApk(), tempHelper, "Deploying View helper");
            push(serial, agent, tempAgent, "Deploying JVMTI agent");
            push(serial, localCommand, tempCommand, "Deploying mutation command");
            adbRun(serial, List.of("shell", "chmod", "0644", tempHelper, tempAgent, tempCommand),
                    COMMAND_TIMEOUT, "Preparing mutation payloads");

            String remoteDir = "code_cache/uiinspector";
            String helperFile = remoteDir + "/helper-" + captureId + ".apk";
            String agentFile = remoteDir + "/libuiinspector_agent_" + captureId + ".so";
            String commandFile = remoteDir + "/command-" + captureId + ".json";
            String resultFile = remoteDir + "/mutation-result-" + captureId + ".json";

            appShell(app, serial, packageName,
                    List.of("mkdir", "-p", remoteDir), "Creating mutation directory");
            appShell(app, serial, packageName,
                    List.of("cp", tempHelper, helperFile), "Copying View helper");
            appShell(app, serial, packageName,
                    List.of("cp", tempAgent, agentFile), "Copying JVMTI agent");
            appShell(app, serial, packageName,
                    List.of("cp", tempCommand, commandFile), "Copying mutation command");
            appShell(app, serial, packageName,
                    List.of("chmod", "0444", helperFile, agentFile, commandFile),
                    "Protecting mutation payloads");

            String remoteAgent = app.path + "/" + agentFile;
            String remoteHelper = app.path + "/" + helperFile;
            String remoteCommand = app.path + "/" + commandFile;
            String remoteResult = app.path + "/" + resultFile;

            adbRun(serial, List.of(
                    "shell", "cmd", "activity", "attach-agent", process.processName(),
                    remoteAgent + "=" + remoteHelper + "," + remoteCommand + "," + remoteResult),
                    ATTACH_TIMEOUT, "Attaching mutation JVMTI agent");
            LOG.info("Mutation attach-agent succeeded for " + process.processName()
                    + " (pid=" + process.pid() + ", runAs=" + app.usesRunAs + ")");
            return waitForMutation(app, serial, packageName, resultFile);
        } catch (IOException error) {
            LOG.log(Level.WARNING, "Mutation failed for " + process.processName()
                    + " (pid=" + process.pid() + "): " + error.getMessage(), error);
            throw new EnhancedCaptureUnavailableException(error.getMessage(), error);
        } finally {
            AppDir cleanupApp = app;
            Path local = localCommand;
            CLEANUP.submit(() -> {
                cleanupMutation(serial, packageName, captureId, tempHelper, tempAgent, tempCommand, cleanupApp);
                if (local != null) {
                    try { Files.deleteIfExists(local); } catch (IOException ignored) {}
                }
            });
        }
    }

    private MutationResult waitForMutation(AppDir app, String serial, String packageName, String relativePath)
            throws IOException, InterruptedException, EnhancedCaptureUnavailableException {
        long deadline = System.nanoTime() + CAPTURE_TIMEOUT.toNanos();
        String lastError = "";
        int attempts = 0;
        while (System.nanoTime() < deadline) {
            String stdout;
            try {
                stdout = appRead(app, serial, packageName, relativePath);
            } catch (IOException e) {
                lastError = e.getMessage();
                Thread.sleep(100);
                continue;
            }
            attempts++;
            if (stdout != null && !stdout.isBlank()) {
                LOG.info("Mutation result found after " + attempts + " poll attempts");
                try {
                    return MutationJson.decodeResult(
                            AdbJsonOutput.payload(stdout, "Target mutation result"));
                } catch (IOException | RuntimeException error) {
                    lastError = error.getMessage();
                }
            } else {
                lastError = "";
            }
            Thread.sleep(100);
        }
        throw new EnhancedCaptureUnavailableException(
                "Timed out waiting for the target mutation result."
                        + (lastError == null || lastError.isBlank() ? "" : " " + lastError.strip()));
    }

    private void cleanupMutation(String serial, String packageName, String captureId,
            String tempHelper, String tempAgent, String tempCommand, AppDir app) {
        try {
            adb.run(withSerial(serial, List.of(
                    "shell", "rm", "-f", tempHelper, tempAgent, tempCommand)), COMMAND_TIMEOUT);
            if (app != null) {
                appRemove(app, serial, packageName, List.of(
                        "code_cache/uiinspector/helper-" + captureId + ".apk",
                        "code_cache/uiinspector/libuiinspector_agent_" + captureId + ".so",
                        "code_cache/uiinspector/command-" + captureId + ".json",
                        "code_cache/uiinspector/mutation-result-" + captureId + ".json"));
            }
        } catch (Exception ignored) {
            // Cleanup is best effort.
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static List<String> withSerial(String serial, List<String> arguments) {
        java.util.ArrayList<String> command = new java.util.ArrayList<>(arguments.size() + 2);
        command.add("-s");
        command.add(serial);
        command.addAll(arguments);
        return command;
    }

    /** Wrap a shell script in single quotes so the device shell receives it as one argument. */
    private static String shellQuote(String script) {
        return "'" + script.replace("'", "'\''") + "'";
    }

    private static String safe(String value) {
        String safe = value == null ? "capture" : value.replaceAll("[^A-Za-z0-9_-]", "-");
        return safe.isBlank() ? "capture" : safe;
    }

    /**
     * Resolved app-private directory, along with whether {@code run-as}
     * is available for this package.
     */
    private record AppDir(String path, boolean usesRunAs) {}
}
