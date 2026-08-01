package com.company.uiinspector.plugin.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.company.uiinspector.plugin.adb.AdbClient;
import com.company.uiinspector.plugin.adb.CommandResult;
import com.company.uiinspector.protocol.CaptureScope;
import com.company.uiinspector.protocol.InspectionSnapshot;
import com.company.uiinspector.protocol.SnapshotJson;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SemanticCaptureTest {
    @Test
    void readsTargetSnapshotThroughContentProviderWithoutShellQuoting() throws Exception {
        var expected = new InspectionSnapshot(
                1, 10L, "unknown", CaptureScope.TARGET_PROCESS, "com.example", "com.example",
                List.of(), List.of(), Map.of(), List.of(), null);
        var adb = new SnapshotAdb(SnapshotJson.encode(expected));

        var actual = new SemanticCapture(adb).capture(
                "device", CaptureScope.TARGET_PROCESS, "com.example");

        assertEquals("com.example", actual.targetPackage());
        assertEquals("device", actual.deviceSerial());
        assertEquals(List.of(
                "-s", "device", "exec-out", "content", "read", "--uri",
                "content://com.company.uiinspector.collector/snapshot?scope=target&package=com.example&maxNodes=10000&maxDepth=200"),
                adb.commands.get(0));
    }

    private static final class SnapshotAdb implements AdbClient {
        final List<List<String>> commands = new ArrayList<>();
        final byte[] snapshot;

        SnapshotAdb(String snapshot) {
            this.snapshot = snapshot.getBytes(StandardCharsets.UTF_8);
        }

        @Override public CommandResult run(List<String> arguments, Duration timeout) {
            commands.add(List.copyOf(arguments));
            return new CommandResult(0, new String(snapshot, StandardCharsets.UTF_8), "", snapshot);
        }
    }
}
