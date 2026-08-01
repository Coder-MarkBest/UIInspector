package com.company.uiinspector.protocol;

import java.util.List;
import java.util.Map;

public record InspectionSnapshot(
        int schemaVersion,
        long capturedAtEpochMs,
        String deviceSerial,
        CaptureScope scope,
        String targetPackage,
        String targetProcess,
        List<ProcessInfo> processes,
        List<WindowSnapshot> windows,
        Map<String, UiNode> nodes,
        List<Diagnostic> diagnostics,
        Screenshot screenshot) {
    public static final int CURRENT_SCHEMA_VERSION = 1;
}
