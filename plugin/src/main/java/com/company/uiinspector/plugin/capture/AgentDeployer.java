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
    private static final Duration CAPTURE_TIMEOUT = Duration.ofSeconds(20);

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

            appShell(app, serial, packageName,
                    List.of("mkdir", "-p", remoteDir), "Creating capture directory");
            appShell(app, serial, packageName,
                    List.of("cp", tempHelper, helperFile), "Copying View helper");
            appShell(app, serial, packageName,
                    List.of("cp", tempAgent, agentFile), "Copying JVMTI agent");
            appShell(app, serial, packageName,
                    List.of("chmod", "0444", helperFile, agentFile),
                    "Protecting dynamic code payloads");

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
                LOG.info("Snapshot file found after " + attempts + " poll attempts");
                try {
                    return SnapshotJson.decode(
                            AdbJsonOutput.payload(stdout, "Target View snapshot"));
                } catch (IOException | RuntimeException error) {
                    lastError = error.getMessage();
                    LOG.warning("Failed to parse snapshot (attempt " + attempts + "): " + lastError);
                }
            } else {
                if (attempts == 1 || attempts % 20 == 0) {
                    LOG.fine("Polling for snapshot (attempt " + attempts + ")");
                }
            }
            Thread.sleep(100);
        }
        String detail = (lastError == null || lastError.isBlank()) ? "" : " Last error: " + lastError.strip();
        LOG.warning("Timed out waiting for snapshot after " + attempts + " attempts." + detail);
        throw new EnhancedCaptureUnavailableException(
                "Timed out waiting for the target View snapshot." + detail);
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
