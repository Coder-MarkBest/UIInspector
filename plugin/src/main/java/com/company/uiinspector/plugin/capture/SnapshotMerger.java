package com.company.uiinspector.plugin.capture;

import com.company.uiinspector.protocol.Diagnostic;
import com.company.uiinspector.protocol.InspectionSnapshot;
import com.company.uiinspector.protocol.ProcessInfo;
import com.company.uiinspector.protocol.Screenshot;
import java.util.ArrayList;
import java.util.List;

public final class SnapshotMerger {
    private SnapshotMerger() {}

    public static InspectionSnapshot merge(
            InspectionSnapshot semantic,
            InspectionSnapshot enhanced,
            Screenshot screenshot,
            List<ProcessInfo> processes,
            Diagnostic degradation) {
        InspectionSnapshot tree = enhanced == null ? semantic : enhanced;
        List<Diagnostic> diagnostics = new ArrayList<>(semantic.diagnostics());
        if (enhanced != null) diagnostics.addAll(enhanced.diagnostics());
        if (degradation != null) diagnostics.add(degradation);
        return new InspectionSnapshot(
                InspectionSnapshot.CURRENT_SCHEMA_VERSION,
                Math.max(semantic.capturedAtEpochMs(), tree.capturedAtEpochMs()),
                semantic.deviceSerial(),
                semantic.scope(),
                semantic.targetPackage(),
                semantic.targetProcess(),
                List.copyOf(processes),
                tree.windows(),
                tree.nodes(),
                List.copyOf(diagnostics),
                screenshot);
    }
}
