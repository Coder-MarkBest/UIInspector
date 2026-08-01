package com.company.uiinspector.plugin.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.company.uiinspector.protocol.CaptureScope;
import com.company.uiinspector.protocol.CaptureSource;
import com.company.uiinspector.protocol.Diagnostic;
import com.company.uiinspector.protocol.InspectionSnapshot;
import com.company.uiinspector.protocol.ProcessInfo;
import com.company.uiinspector.protocol.Rect;
import com.company.uiinspector.protocol.Screenshot;
import com.company.uiinspector.protocol.WindowSnapshot;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SnapshotMergerTest {
    @Test
    void targetScopeUsesRealViewsAndCarriesDesktopMetadata() {
        var semantic = snapshot(CaptureSource.SEMANTIC, "semantic", List.of(new Diagnostic("INFO", "semantic", "ok")));
        var enhanced = snapshot(CaptureSource.JVMTI, "real", List.of());
        var process = new ProcessInfo(42, "u0_a1", "com.example", "com.example", true);
        var screenshot = new Screenshot("image/png", "data:image/png;base64,AA==", null);

        var merged = SnapshotMerger.merge(semantic, enhanced, screenshot, List.of(process), null);

        assertEquals(CaptureSource.JVMTI, merged.windows().get(0).source());
        assertEquals("real", merged.windows().get(0).title());
        assertEquals(42, merged.processes().get(0).pid());
        assertEquals(screenshot, merged.screenshot());
        assertEquals("semantic", merged.diagnostics().get(0).code());
    }

    @Test
    void unavailableEnhancementKeepsSemanticTreeAndExplainsDegradation() {
        var semantic = snapshot(CaptureSource.SEMANTIC, "semantic", List.of());
        var fallback = new Diagnostic("WARN", "enhanced_unavailable", "Target is not debuggable.");

        var merged = SnapshotMerger.merge(semantic, null, null, List.of(), fallback);

        assertEquals(CaptureSource.SEMANTIC, merged.windows().get(0).source());
        assertEquals("enhanced_unavailable", merged.diagnostics().get(0).code());
    }

    private static InspectionSnapshot snapshot(CaptureSource source, String title, List<Diagnostic> diagnostics) {
        var window = new WindowSnapshot(
                source.name() + ":window", source, "com.example", "com.example", 0,
                "APPLICATION", 1, null, List.of(), new Rect(0, 0, 100, 100),
                true, true, title, null, List.of());
        return new InspectionSnapshot(
                1, 10L, "device", CaptureScope.TARGET_PROCESS, "com.example", "com.example",
                List.of(), List.of(window), Map.of(), diagnostics, null);
    }
}
