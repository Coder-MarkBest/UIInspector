package com.company.uiinspector.plugin.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.uiinspector.plugin.adb.AdbClient;
import com.company.uiinspector.plugin.adb.CommandResult;
import com.company.uiinspector.plugin.adb.RunningProcess;
import com.company.uiinspector.protocol.CaptureScope;
import com.company.uiinspector.protocol.InspectionSnapshot;
import com.company.uiinspector.protocol.SnapshotJson;
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

    private static final class RecordingAdb implements AdbClient {
        final List<List<String>> commands = new ArrayList<>();
        final String snapshot;

        RecordingAdb(String snapshot) { this.snapshot = snapshot; }

        @Override public CommandResult run(List<String> arguments, Duration timeout) {
            commands.add(List.copyOf(arguments));
            String output = "";
            if (arguments.contains("pwd")) output = "/data/user/0/com.example\n";
            if (arguments.contains("cat")) output = snapshot;
            byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
            return new CommandResult(0, output, "", bytes);
        }
    }
}
