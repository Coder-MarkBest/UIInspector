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
import java.util.function.Supplier;

public final class AgentDeployer {
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration ATTACH_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration CAPTURE_TIMEOUT = Duration.ofSeconds(15);

    private final AdbClient adb;
    private final AgentPayloads payloads;
    private final Supplier<String> captureIds;

    public AgentDeployer(AdbClient adb, AgentPayloads payloads, Supplier<String> captureIds) {
        this.adb = adb;
        this.payloads = payloads;
        this.captureIds = captureIds;
    }

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
        String privateDirectory = null;
        try {
            privateDirectory = command(serial, List.of("shell", "run-as", packageName, "pwd"), COMMAND_TIMEOUT,
                    "Checking debuggable target").stdout().strip();
            if (privateDirectory.isBlank()) {
                throw new EnhancedCaptureUnavailableException("run-as did not return the target data directory.");
            }
            command(serial, List.of("push", payloads.helperApk().toString(), tempHelper), COMMAND_TIMEOUT, "Deploying View helper");
            command(serial, List.of("push", agent.toString(), tempAgent), COMMAND_TIMEOUT, "Deploying JVMTI agent");
            command(serial, List.of("shell", "chmod", "0644", tempHelper, tempAgent), COMMAND_TIMEOUT, "Preparing agent payloads");

            String remoteDirectory = privateDirectory + "/code_cache/uiinspector";
            String remoteHelper = remoteDirectory + "/helper-" + captureId + ".apk";
            String remoteAgent = remoteDirectory + "/libuiinspector_agent_" + captureId + ".so";
            String remoteSnapshot = remoteDirectory + "/snapshot-" + captureId + ".json";
            command(serial, List.of("shell", "run-as", packageName, "mkdir", "-p", "code_cache/uiinspector"),
                    COMMAND_TIMEOUT, "Creating target capture directory");
            command(serial, List.of("shell", "run-as", packageName, "cp", tempHelper,
                    "code_cache/uiinspector/helper-" + captureId + ".apk"), COMMAND_TIMEOUT, "Copying View helper");
            command(serial, List.of("shell", "run-as", packageName, "cp", tempAgent,
                    "code_cache/uiinspector/libuiinspector_agent_" + captureId + ".so"), COMMAND_TIMEOUT, "Copying JVMTI agent");
            command(serial, List.of(
                            "shell", "run-as", packageName, "chmod", "0444",
                            "code_cache/uiinspector/helper-" + captureId + ".apk",
                            "code_cache/uiinspector/libuiinspector_agent_" + captureId + ".so"),
                    COMMAND_TIMEOUT, "Protecting dynamic code payloads");

            command(serial, List.of(
                            "shell", "cmd", "activity", "attach-agent", process.processName(),
                            remoteAgent + "=" + remoteHelper + "," + remoteSnapshot),
                    ATTACH_TIMEOUT, "Attaching JVMTI agent");
            return waitForSnapshot(serial, packageName, "code_cache/uiinspector/snapshot-" + captureId + ".json");
        } catch (IOException error) {
            throw new EnhancedCaptureUnavailableException(error.getMessage(), error);
        } finally {
            cleanup(serial, packageName, captureId, tempHelper, tempAgent, privateDirectory != null);
        }
    }

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
        String privateDirectory = null;
        Path localCommand = null;
        try {
            localCommand = Files.createTempFile("uiinspector-mutation-", ".json");
            Files.writeString(localCommand, MutationJson.encodeCommand(mutation));
            privateDirectory = command(serial, List.of("shell", "run-as", packageName, "pwd"), COMMAND_TIMEOUT,
                    "Checking debuggable target").stdout().strip();
            if (privateDirectory.isBlank()) {
                throw new EnhancedCaptureUnavailableException("run-as did not return the target data directory.");
            }
            command(serial, List.of("push", payloads.helperApk().toString(), tempHelper),
                    COMMAND_TIMEOUT, "Deploying View helper");
            command(serial, List.of("push", agent.toString(), tempAgent),
                    COMMAND_TIMEOUT, "Deploying JVMTI agent");
            command(serial, List.of("push", localCommand.toString(), tempCommand),
                    COMMAND_TIMEOUT, "Deploying mutation command");
            command(serial, List.of("shell", "chmod", "0644", tempHelper, tempAgent, tempCommand),
                    COMMAND_TIMEOUT, "Preparing mutation payloads");

            String remoteDirectory = privateDirectory + "/code_cache/uiinspector";
            String remoteHelper = remoteDirectory + "/helper-" + captureId + ".apk";
            String remoteAgent = remoteDirectory + "/libuiinspector_agent_" + captureId + ".so";
            String remoteCommand = remoteDirectory + "/command-" + captureId + ".json";
            String remoteResult = remoteDirectory + "/mutation-result-" + captureId + ".json";
            command(serial, List.of("shell", "run-as", packageName, "mkdir", "-p", "code_cache/uiinspector"),
                    COMMAND_TIMEOUT, "Creating target mutation directory");
            command(serial, List.of("shell", "run-as", packageName, "cp", tempHelper,
                    "code_cache/uiinspector/helper-" + captureId + ".apk"),
                    COMMAND_TIMEOUT, "Copying View helper");
            command(serial, List.of("shell", "run-as", packageName, "cp", tempAgent,
                    "code_cache/uiinspector/libuiinspector_agent_" + captureId + ".so"),
                    COMMAND_TIMEOUT, "Copying JVMTI agent");
            command(serial, List.of("shell", "run-as", packageName, "cp", tempCommand,
                    "code_cache/uiinspector/command-" + captureId + ".json"),
                    COMMAND_TIMEOUT, "Copying mutation command");
            command(serial, List.of(
                            "shell", "run-as", packageName, "chmod", "0444",
                            "code_cache/uiinspector/helper-" + captureId + ".apk",
                            "code_cache/uiinspector/libuiinspector_agent_" + captureId + ".so",
                            "code_cache/uiinspector/command-" + captureId + ".json"),
                    COMMAND_TIMEOUT, "Protecting mutation payloads");

            command(serial, List.of(
                            "shell", "cmd", "activity", "attach-agent", process.processName(),
                            remoteAgent + "=" + remoteHelper + "," + remoteCommand + "," + remoteResult),
                    ATTACH_TIMEOUT, "Attaching mutation JVMTI agent");
            return waitForMutation(
                    serial, packageName, "code_cache/uiinspector/mutation-result-" + captureId + ".json");
        } catch (IOException error) {
            throw new EnhancedCaptureUnavailableException(error.getMessage(), error);
        } finally {
            cleanupMutation(serial, packageName, captureId,
                    tempHelper, tempAgent, tempCommand, privateDirectory != null);
            if (localCommand != null) {
                try {
                    Files.deleteIfExists(localCommand);
                } catch (IOException ignored) {
                    // Remote cleanup and the mutation result take precedence.
                }
            }
        }
    }

    private InspectionSnapshot waitForSnapshot(String serial, String packageName, String relativePath)
            throws IOException, InterruptedException, EnhancedCaptureUnavailableException {
        long deadline = System.nanoTime() + CAPTURE_TIMEOUT.toNanos();
        String lastError = "";
        while (System.nanoTime() < deadline) {
            var result = adb.run(withSerial(serial, List.of(
                    "exec-out", "run-as", packageName, "cat", relativePath)), COMMAND_TIMEOUT);
            if (result.exitCode() == 0 && !result.stdout().isBlank()) {
                try {
                    return SnapshotJson.decode(result.stdout());
                } catch (RuntimeException error) {
                    lastError = error.getMessage();
                }
            } else {
                lastError = result.stderr();
            }
            Thread.sleep(100);
        }
        throw new EnhancedCaptureUnavailableException(
                "Timed out waiting for the target View snapshot." + (lastError == null || lastError.isBlank() ? "" : " " + lastError.strip()));
    }

    private MutationResult waitForMutation(String serial, String packageName, String relativePath)
            throws IOException, InterruptedException, EnhancedCaptureUnavailableException {
        long deadline = System.nanoTime() + CAPTURE_TIMEOUT.toNanos();
        String lastError = "";
        while (System.nanoTime() < deadline) {
            var result = adb.run(withSerial(serial, List.of(
                    "exec-out", "run-as", packageName, "cat", relativePath)), COMMAND_TIMEOUT);
            if (result.exitCode() == 0 && !result.stdout().isBlank()) {
                try {
                    return MutationJson.decodeResult(result.stdout());
                } catch (RuntimeException error) {
                    lastError = error.getMessage();
                }
            } else {
                lastError = result.stderr();
            }
            Thread.sleep(100);
        }
        throw new EnhancedCaptureUnavailableException(
                "Timed out waiting for the target mutation result."
                        + (lastError == null || lastError.isBlank() ? "" : " " + lastError.strip()));
    }

    private com.company.uiinspector.plugin.adb.CommandResult command(
            String serial, List<String> arguments, Duration timeout, String operation)
            throws IOException, InterruptedException {
        return adb.run(withSerial(serial, arguments), timeout).requireSuccess(operation);
    }

    private static List<String> withSerial(String serial, List<String> arguments) {
        java.util.ArrayList<String> command = new java.util.ArrayList<>(arguments.size() + 2);
        command.add("-s");
        command.add(serial);
        command.addAll(arguments);
        return command;
    }

    private void cleanup(
            String serial, String packageName, String captureId, String tempHelper, String tempAgent, boolean privateReady) {
        try {
            adb.run(withSerial(serial, List.of("shell", "rm", "-f", tempHelper, tempAgent)), COMMAND_TIMEOUT);
            if (privateReady) {
                adb.run(withSerial(serial, List.of(
                        "shell", "run-as", packageName, "rm", "-f",
                        "code_cache/uiinspector/helper-" + captureId + ".apk",
                        "code_cache/uiinspector/libuiinspector_agent_" + captureId + ".so",
                        "code_cache/uiinspector/snapshot-" + captureId + ".json")), COMMAND_TIMEOUT);
            }
        } catch (Exception ignored) {
            // Cleanup is best effort and must not replace the capture result.
        }
    }

    private void cleanupMutation(
            String serial,
            String packageName,
            String captureId,
            String tempHelper,
            String tempAgent,
            String tempCommand,
            boolean privateReady) {
        try {
            adb.run(withSerial(serial, List.of(
                    "shell", "rm", "-f", tempHelper, tempAgent, tempCommand)), COMMAND_TIMEOUT);
            if (privateReady) {
                adb.run(withSerial(serial, List.of(
                        "shell", "run-as", packageName, "rm", "-f",
                        "code_cache/uiinspector/helper-" + captureId + ".apk",
                        "code_cache/uiinspector/libuiinspector_agent_" + captureId + ".so",
                        "code_cache/uiinspector/command-" + captureId + ".json",
                        "code_cache/uiinspector/mutation-result-" + captureId + ".json")), COMMAND_TIMEOUT);
            }
        } catch (Exception ignored) {
            // Cleanup is best effort and must not replace the mutation result.
        }
    }

    private static String safe(String value) {
        String safe = value == null ? "capture" : value.replaceAll("[^A-Za-z0-9_-]", "-");
        return safe.isBlank() ? "capture" : safe;
    }
}
