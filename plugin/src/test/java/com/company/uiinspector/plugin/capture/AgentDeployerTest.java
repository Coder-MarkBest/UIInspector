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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentDeployerTest {

    private static final String PRIVATE_DIR = "/data/user/0/com.example";
    private static final String CAPTURE_ID = "capture-1";

    private static InspectionSnapshot sampleSnapshot() {
        return new InspectionSnapshot(
                1, 20L, "device", CaptureScope.TARGET_PROCESS, "com.example", "com.example",
                List.of(), List.of(), Map.of(), List.of(), null);
    }

    // ── run-as path (default) ─────────────────────────────────────────

    @Test
    void deploysViaRunAsAttachesAndReadsSnapshot() throws Exception {
        var snapshot = sampleSnapshot();
        var adb = new RecordingAdb(SnapshotJson.encode(snapshot));
        var payloads = new AgentPayloads(
                Path.of("helper.apk"),
                Map.of("arm64-v8a", Path.of("arm64", "libuiinspector_agent.so")));
        var deployer = new AgentDeployer(adb, payloads, () -> CAPTURE_ID);

        var result = deployer.capture(
                "device", new RunningProcess("u0_a1", 123, "com.example", "com.example"), "arm64-v8a");

        assertEquals("com.example", result.targetPackage());
        assertTrue(adb.hasElement("run-as") && adb.hasElement("pwd"));
        assertTrue(adb.hasElement("push"));
        assertTrue(adb.hasArgContaining("helper.apk"));
        assertTrue(adb.hasArgContaining("libuiinspector_agent.so"));
        assertTrue(adb.hasElement("attach-agent") && adb.hasElement("com.example")
                && adb.hasArgContaining("libuiinspector_agent_" + CAPTURE_ID + ".so"));
    }

    @Test
    void readsPrivateSnapshotAfterWindowsAdbDiagnosticPrefix() throws Exception {
        var snapshot = sampleSnapshot();
        var adb = new RecordingAdb(
                "adb.exe: warning: transport emitted a host diagnostic\r\n"
                        + SnapshotJson.encode(snapshot) + "\r\n");
        var payloads = new AgentPayloads(
                Path.of("helper.apk"),
                Map.of("arm64-v8a", Path.of("arm64", "libuiinspector_agent.so")));

        var result = new AgentDeployer(adb, payloads, () -> CAPTURE_ID).capture(
                "device", new RunningProcess("u0_a1", 123, "com.example", "com.example"), "arm64-v8a");

        assertEquals("com.example", result.targetPackage());
    }

    @Test
    void rejectsUnsupportedAbiBeforeAnyAdbMutation() {
        var adb = new RecordingAdb("{}");
        var deployer = new AgentDeployer(
                adb, new AgentPayloads(Path.of("helper.apk"), Map.of()), () -> CAPTURE_ID);

        var error = assertThrows(EnhancedCaptureUnavailableException.class,
                () -> deployer.capture(
                        "device", new RunningProcess("u0_a1", 123, "com.example", "com.example"), "mips"));

        assertEquals("No JVMTI agent payload for device ABI mips.", error.getMessage());
        assertEquals(List.of(), adb.commands);
    }

    // ── root fallback path ────────────────────────────────────────────

    @Test
    void fallsBackToRootWhenRunAsUnavailable() throws Exception {
        var snapshot = sampleSnapshot();
        var adb = new RootFallbackAdb(SnapshotJson.encode(snapshot));
        var payloads = new AgentPayloads(
                Path.of("helper.apk"),
                Map.of("arm64-v8a", Path.of("arm64", "libuiinspector_agent.so")));
        var deployer = new AgentDeployer(adb, payloads, () -> CAPTURE_ID);

        var result = deployer.capture(
                "device", new RunningProcess("u0_a1", 123, "com.example", "com.example"), "arm64-v8a");

        assertEquals("com.example", result.targetPackage());
        // Root fallback: should use direct paths, not run-as
        assertTrue(adb.hasArgContaining("/data/data/com.example/code_cache/uiinspector/snapshot-" + CAPTURE_ID + ".json"));
        assertTrue(adb.hasArgContaining("/data/data/com.example/code_cache/uiinspector"));
        assertTrue(adb.hasElement("attach-agent"));
        // Should NOT contain any run-as calls (except the failed pwd attempt)
        assertTrue(adb.commands.stream().noneMatch(c -> c.contains("run-as") && !c.contains("pwd")));
    }

    @Test
    void mutationFallsBackToRootWhenRunAsUnavailable() throws Exception {
        var snapshot = sampleSnapshot();
        var mutationResult = new MutationResult(
                1, true, "ok", "Applied", "1.0", "0.5", snapshot);
        var adb = new RootFallbackAdb(SnapshotJson.encode(snapshot), MutationJson.encodeResult(mutationResult));
        var payloads = new AgentPayloads(
                Path.of("helper.apk"),
                Map.of("arm64-v8a", Path.of("arm64", "libuiinspector_agent.so")));
        var deployer = new AgentDeployer(adb, payloads, () -> CAPTURE_ID);
        var command = new MutationCommand(
                1, "v:n:0:0", "android.view.View", "com.example:id/root",
                "alpha", "setter:setAlpha", EditorTarget.VIEW, EditorValueType.FLOAT, "0.5");

        var result = deployer.mutate(
                "device", new RunningProcess("u0_a1", 123, "com.example", "com.example"),
                "arm64-v8a", command);

        assertTrue(result.success());
        assertTrue(adb.hasElement("attach-agent"));
        assertTrue(adb.hasArgContaining("/data/data/com.example/code_cache/uiinspector/mutation-result-" + CAPTURE_ID + ".json"));
    }

    // ── mutation (run-as path) ────────────────────────────────────────

    @Test
    void deploysCommandFileAttachesMutationModeReadsResultAndCleansUp() throws Exception {
        var snapshot = sampleSnapshot();
        var mutationResult = new MutationResult(
                1, true, "ok", "Applied", "1.0", "0.5", snapshot);
        var adb = new RecordingAdb(SnapshotJson.encode(snapshot), MutationJson.encodeResult(mutationResult));
        var payloads = new AgentPayloads(
                Path.of("helper.apk"),
                Map.of("arm64-v8a", Path.of("arm64", "libuiinspector_agent.so")));
        var deployer = new AgentDeployer(adb, payloads, () -> CAPTURE_ID);
        var command = new MutationCommand(
                1, "v:n:0:0", "android.view.View", "com.example:id/root",
                "alpha", "setter:setAlpha", EditorTarget.VIEW, EditorValueType.FLOAT, "0.5");

        var result = deployer.mutate(
                "device", new RunningProcess("u0_a1", 123, "com.example", "com.example"),
                "arm64-v8a", command);

        assertTrue(result.success());
        assertTrue(adb.hasElement("attach-agent"));
        assertTrue(adb.hasArgContaining("command-" + CAPTURE_ID + ".json"));
        assertTrue(adb.hasArgContaining("mutation-result-" + CAPTURE_ID + ".json"));
        assertTrue(adb.hasElement("rm") && adb.hasArgContaining("command-" + CAPTURE_ID + ".json"));
    }

    @Test
    void readsMutationResultAfterWindowsAdbDiagnosticPrefix() throws Exception {
        var snapshot = sampleSnapshot();
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
                () -> CAPTURE_ID);
        var command = new MutationCommand(
                1, "v:n:0:0", "android.view.View", "com.example:id/root",
                "alpha", "setter:setAlpha", EditorTarget.VIEW, EditorValueType.FLOAT, "0.5");

        var result = deployer.mutate(
                "device", new RunningProcess("u0_a1", 123, "com.example", "com.example"),
                "arm64-v8a", command);

        assertTrue(result.success());
        assertEquals("0.5", result.appliedValue());
    }

    // ── test doubles ──────────────────────────────────────────────────

    /** Standard mock: run-as works, returns private dir via pwd. */
    private static final class RecordingAdb implements AdbClient {
        final List<List<String>> commands = new ArrayList<>();
        final String snapshot;
        final String mutationResult;

        RecordingAdb(String snapshot) { this(snapshot, null); }

        RecordingAdb(String snapshot, String mutationResult) {
            this.snapshot = snapshot;
            this.mutationResult = mutationResult;
        }

        @Override
        public CommandResult run(List<String> arguments, Duration timeout) throws java.io.IOException {
            commands.add(List.copyOf(arguments));
            String output = "";
            if (arguments.contains("pwd")) output = PRIVATE_DIR + "\n";
            if (arguments.contains("cat")) {
                output = arguments.stream().anyMatch(v -> v.contains("mutation-result"))
                        ? mutationResult : snapshot;
            }
            byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
            return new CommandResult(0, output, "", bytes);
        }

        /** True if any recorded command list contains the exact element. */
        boolean hasElement(String element) {
            return commands.stream().anyMatch(c -> c.contains(element));
        }

        /** True if any recorded command has an element whose toString contains the substring. */
        boolean hasArgContaining(String substring) {
            return commands.stream().anyMatch(
                    c -> c.stream().anyMatch(e -> e.contains(substring)));
        }
    }

    /**
     * Simulates a root device where run-as is unavailable (system packages).
     * run-as pwd returns exit 1; id -u returns "0".
     */
    private static final class RootFallbackAdb implements AdbClient {
        final List<List<String>> commands = new ArrayList<>();
        final String snapshot;
        final String mutationResult;

        RootFallbackAdb(String snapshot) { this(snapshot, null); }

        RootFallbackAdb(String snapshot, String mutationResult) {
            this.snapshot = snapshot;
            this.mutationResult = mutationResult;
        }

        @Override
        public CommandResult run(List<String> arguments, Duration timeout) throws java.io.IOException {
            commands.add(List.copyOf(arguments));
            String output = "";
            int exitCode = 0;
            if (arguments.contains("pwd")) {
                output = "run-as: package not an application: com.example\n";
                exitCode = 1;
            }
            if (arguments.contains("id") && arguments.contains("-u")) {
                output = "0\n";
            }
            if (arguments.contains("cat")) {
                output = arguments.stream().anyMatch(v -> v.contains("mutation-result"))
                        ? mutationResult : snapshot;
            }
            byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
            return new CommandResult(exitCode, output, "", bytes);
        }

        boolean hasElement(String element) {
            return commands.stream().anyMatch(c -> c.contains(element));
        }

        boolean hasArgContaining(String substring) {
            return commands.stream().anyMatch(
                    c -> c.stream().anyMatch(e -> e.contains(substring)));
        }
    }
}
