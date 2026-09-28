package com.company.uiinspector.plugin.capture;

import com.company.uiinspector.plugin.adb.AdbClient;
import com.company.uiinspector.protocol.CaptureScope;
import com.company.uiinspector.protocol.InspectionSnapshot;
import com.company.uiinspector.protocol.Rect;
import com.company.uiinspector.protocol.SnapshotJson;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

public final class SemanticCapture {
    private final AdbClient adb;

    public SemanticCapture(AdbClient adb) {
        this.adb = adb;
    }

    public void highlight(String serial, Rect bounds) throws IOException, InterruptedException {
        StringBuilder uri = new StringBuilder(
                "content://com.company.uiinspector.collector/highlight");
        if (bounds != null) {
            uri.append("?bounds=").append(bounds.left()).append(",")
                    .append(bounds.top()).append(",")
                    .append(bounds.right()).append(",")
                    .append(bounds.bottom());
        }
        adb.run(List.of("-s", serial, "exec-out", "content", "read", "--uri", uri.toString()),
                Duration.ofSeconds(5));
    }

    public InspectionSnapshot capture(String serial, CaptureScope scope, String packageName)
            throws IOException, InterruptedException {
        StringBuilder uri = new StringBuilder(
                "content://com.company.uiinspector.collector/snapshot?scope=")
                .append(switch (scope) {
                    case TARGET_PROCESS -> "target";
                    case PROCESS_WINDOWS -> "process";
                    case GLOBAL -> "global";
                });
        if ((scope == CaptureScope.TARGET_PROCESS || scope == CaptureScope.PROCESS_WINDOWS)
                && packageName != null) {
            uri.append("&package=").append(URLEncoder.encode(packageName, StandardCharsets.UTF_8));
        }
        uri.append("&maxNodes=5000&maxDepth=200");
        var result = adb.run(List.of(
                        "-s", serial, "exec-out", "content", "read", "--uri", uri.toString()),
                Duration.ofSeconds(15)).requireSuccess("Semantic UI capture");
        InspectionSnapshot snapshot = SnapshotJson.decode(
                AdbJsonOutput.payload(result.stdout(), "Semantic UI capture"));
        return new InspectionSnapshot(
                snapshot.schemaVersion(),
                snapshot.capturedAtEpochMs(),
                serial,
                scope,
                packageName,
                snapshot.targetProcess(),
                snapshot.processes(),
                snapshot.windows(),
                snapshot.nodes(),
                snapshot.diagnostics(),
                snapshot.screenshot());
    }
}
