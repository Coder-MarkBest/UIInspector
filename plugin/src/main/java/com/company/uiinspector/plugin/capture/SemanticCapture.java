package com.company.uiinspector.plugin.capture;

import com.company.uiinspector.plugin.adb.AdbClient;
import com.company.uiinspector.protocol.CaptureScope;
import com.company.uiinspector.protocol.InspectionSnapshot;
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

    public InspectionSnapshot capture(String serial, CaptureScope scope, String packageName)
            throws IOException, InterruptedException {
        StringBuilder uri = new StringBuilder(
                "content://com.company.uiinspector.collector/snapshot?scope=")
                .append(scope == CaptureScope.TARGET_PROCESS ? "target" : "global");
        if (scope == CaptureScope.TARGET_PROCESS && packageName != null) {
            uri.append("&package=").append(URLEncoder.encode(packageName, StandardCharsets.UTF_8));
        }
        uri.append("&maxNodes=10000&maxDepth=200");
        var result = adb.run(List.of(
                        "-s", serial, "exec-out", "content", "read", "--uri", uri.toString()),
                Duration.ofSeconds(30)).requireSuccess("Semantic UI capture");
        InspectionSnapshot snapshot = SnapshotJson.decode(result.stdout());
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
