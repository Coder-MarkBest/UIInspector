package com.company.uiinspector.plugin.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.uiinspector.plugin.adb.AdbClient;
import com.company.uiinspector.plugin.adb.CommandResult;
import com.company.uiinspector.plugin.adb.RunningProcess;
import com.company.uiinspector.protocol.CaptureScope;
import com.company.uiinspector.protocol.EditorTarget;
import com.company.uiinspector.protocol.EditorValueType;
import com.company.uiinspector.protocol.InspectionSnapshot;
import com.company.uiinspector.protocol.MutationCommand;
import com.company.uiinspector.protocol.MutationJson;
import com.company.uiinspector.protocol.MutationResult;
import com.company.uiinspector.protocol.SnapshotJson;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentDeployerTest {
    @Test
    void deploysAbiPayloadAttachesAndReadsPrivateSnapshot() throws Exception {
        var snapshot = new InspectionSnapshot(
                1, 20L, "device", CaptureScope.TARGET_PROCESS, "com.example", "com.example",
                List.of(), List.of(), Map.of(), List.of(), null);
        var adb = new RecordingAdb(SnapshotJson.encode(snapshot));
        var payloads = new AgentPayloads(
                Path.of("helper.apk"),
                Map.of("arm64-v8a", Path.of("arm64", "libuiinspector_agent.so")));
        var deployer = new AgentDeployer(adb, payloads, () -> "capture-1");

        var result = deployer.capture(
                "device", new RunningProcess("u0_a1", 123, "com.example", "com.example"), "arm64-v8a");

        assertEquals("com.example", result.targetPackage());
        assertEquals(List.of("-s", "device", "shell", "run-as", "com.example", "pwd"), adb.commands.get(0));
        assertEquals(List.of("-s", "device", "push", "helper.apk", "/data/local/tmp/uiinspector-123-capture-1-helper.apk"), adb.commands.get(1));
        assertEquals(List.of("-s", "device", "shell", "cmd", "activity", "attach-agent", "com.example",
                "/data/user/0/com.example/code_cache/uiinspector/libuiinspector_agent_capture-1.so="
                        + "/data/user/0/com.example/code_cache/uiinspector/helper-capture-1.apk,"
                        + "/data/user/0/com.example/code_cache/uiinspector/snapshot-capture-1.json"),
                adb.commands.stream().filter(command -> command.contains("attach-agent")).findFirst().orElseThrow());
        assertTrue(adb.commands.contains(List.of(
                "-s", "device", "shell", "run-as", "com.example", "chmod", "0444",
                "code_cache/uiinspector/helper-capture-1.apk",
                "code_cache/uiinspector/libuiinspector_agent_capture-1.so")));
    }

    @Test
    void readsPrivateSnapshotAfterWindowsAdbDiagnosticPrefix() throws Exception {
        var snapshot = new InspectionSnapshot(
                1, 20L, "device", CaptureScope.TARGET_PROCESS, "com.example", "com.example",
                List.of(), List.of(), Map.of(), List.of(), null);
        var adb = new RecordingAdb(
                "adb.exe: warning: transport emitted a host diagnostic\r\n"
                        + SnapshotJson.encode(snapshot) + "\r\n");
        var payloads = new AgentPayloads(
                Path.of("helper.apk"),
                Map.of("arm64-v8a", Path.of("arm64", "libuiinspector_agent.so")));

        var result = new AgentDeployer(adb, payloads, () -> "capture-1").capture(
                "device", new RunningProcess("u0_a1", 123, "com.example", "com.example"), "arm64-v8a");

        assertEquals("com.example", result.targetPackage());
    }

    @Test
    void rejectsUnsupportedAbiBeforeAnyAdbMutation() {
        var adb = new RecordingAdb("{}");
        var deployer = new AgentDeployer(
                adb, new AgentPayloads(Path.of("helper.apk"), Map.of()), () -> "capture-1");

        var error = assertThrows(EnhancedCaptureUnavailableException.class,
                () -> deployer.capture(
                        "device", new RunningProcess("u0_a1", 123, "com.example", "com.example"), "mips"));

        assertEquals("No JVMTI agent payload for device ABI mips.", error.getMessage());
        assertEquals(List.of(), adb.commands);
    }

    @Test
    void deploysCommandFileAttachesMutationModeReadsResultAndCleansUp() throws Exception {
        var snapshot = new InspectionSnapshot(
                1, 20L, "device", CaptureScope.TARGET_PROCESS, "com.example", "com.example",
                List.of(), List.of(), Map.of(), List.of(), null);
        var mutationResult = new MutationResult(
                1, true, "ok", "Applied", "1.0", "0.5", snapshot);
        var adb = new RecordingAdb(SnapshotJson.encode(snapshot), MutationJson.encodeResult(mutationResult));
        var payloads = new AgentPayloads(
                Path.of("helper.apk"),
                Map.of("arm64-v8a", Path.of("arm64", "libuiinspector_agent.so")));
        var deployer = new AgentDeployer(adb, payloads, () -> "capture-1");
        var command = new MutationCommand(
                1, "v:n:0:0", "android.view.View", "com.example:id/root",
                "alpha", "setter:setAlpha", EditorTarget.VIEW, EditorValueType.FLOAT, "0.5");

        var result = deployer.mutate(
                "device", new RunningProcess("u0_a1", 123, "com.example", "com.example"),
                "arm64-v8a", command);

        assertTrue(result.success());
        assertEquals("v:n:0:0", adb.decodedCommand.targetId());
        assertEquals("alpha", adb.decodedCommand.propertyName());
        assertEquals("setter:setAlpha", adb.decodedCommand.adapter());
        assertEquals(EditorValueType.FLOAT, adb.decodedCommand.valueType());
        assertEquals("0.5", adb.decodedCommand.value());
        assertTrue(adb.commands.stream().anyMatch(value -> value.equals(List.of(
                "-s", "device", "push", adb.pushedCommandFile,
                "/data/local/tmp/uiinspector-123-capture-1-command.json"))));
        assertEquals(List.of(
                "-s", "device", "shell", "cmd", "activity", "attach-agent", "com.example",
                "/data/user/0/com.example/code_cache/uiinspector/libuiinspector_agent_capture-1.so="
                        + "/data/user/0/com.example/code_cache/uiinspector/helper-capture-1.apk,"
                        + "/data/user/0/com.example/code_cache/uiinspector/command-capture-1.json,"
                        + "/data/user/0/com.example/code_cache/uiinspector/mutation-result-capture-1.json"),
                adb.commands.stream().filter(value -> value.contains("attach-agent")).findFirst().orElseThrow());
        var cleanup = adb.commands.stream()
                .filter(value -> value.contains("rm") && value.contains("run-as"))
                .findFirst().orElseThrow();
        assertTrue(cleanup.contains("code_cache/uiinspector/command-capture-1.json"));
        assertTrue(cleanup.contains("code_cache/uiinspector/mutation-result-capture-1.json"));
    }

    @Test
    void readsMutationResultAfterWindowsAdbDiagnosticPrefix() throws Exception {
        var snapshot = new InspectionSnapshot(
                1, 20L, "device", CaptureScope.TARGET_PROCESS, "com.example", "com.example",
                List.of(), List.of(), Map.of(), List.of(), null);
        var expected = new MutationResult(
                1, true, "ok", "Applied", "1.0", "0.5", snapshot);
        var adb = new RecordingAdb(
                SnapshotJson.encode(snapshot),
                "adb.exe: warning: transport emitted a host diagnostic\r\n"
                        + MutationJson.encodeResult(expected) + "\r\n");
        var deployer = new AgentDeployer(
                adb,
                new AgentPayloads(
                        Path.of("helper.apk"),
                        Map.of("arm64-v8a", Path.of("arm64", "libuiinspector_agent.so"))),
                () -> "capture-1");
        var command = new MutationCommand(
                1, "v:n:0:0", "android.view.View", "com.example:id/root",
                "alpha", "setter:setAlpha", EditorTarget.VIEW, EditorValueType.FLOAT, "0.5");

        var result = deployer.mutate(
                "device", new RunningProcess("u0_a1", 123, "com.example", "com.example"),
                "arm64-v8a", command);

        assertTrue(result.success());
        assertEquals("0.5", result.appliedValue());
    }

    private static final class RecordingAdb implements AdbClient {
        final List<List<String>> commands = new ArrayList<>();
        final String snapshot;
        final String mutationResult;
        String pushedCommandFile;
        MutationCommand decodedCommand;

        RecordingAdb(String snapshot) { this(snapshot, null); }

        RecordingAdb(String snapshot, String mutationResult) {
            this.snapshot = snapshot;
            this.mutationResult = mutationResult;
        }

        @Override public CommandResult run(List<String> arguments, Duration timeout) throws java.io.IOException {
            commands.add(List.copyOf(arguments));
            String output = "";
            if (arguments.contains("pwd")) output = "/data/user/0/com.example\n";
            if (arguments.contains("push") && arguments.get(arguments.size() - 1).endsWith("-command.json")) {
                pushedCommandFile = arguments.get(arguments.size() - 2);
                decodedCommand = MutationJson.decodeCommand(Files.readString(Path.of(pushedCommandFile)));
            }
            if (arguments.contains("cat")) {
                output = arguments.stream().anyMatch(value -> value.contains("mutation-result"))
                        ? mutationResult : snapshot;
            }
            byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
            return new CommandResult(0, output, "", bytes);
        }
    }
}
