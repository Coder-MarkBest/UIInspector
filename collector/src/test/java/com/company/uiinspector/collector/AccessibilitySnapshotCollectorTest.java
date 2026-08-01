package com.company.uiinspector.collector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.company.uiinspector.protocol.CaptureScope;
import com.company.uiinspector.protocol.Rect;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class AccessibilitySnapshotCollectorTest {
    @Test
    public void preservesWindowParentingSeparatelyFromLayerOrder() {
        var root = node("com.example", "FrameLayout", Map.of("class", "FrameLayout"));
        var dialog = window(9, 7, 0, 4, "Dialog", "com.example", root, List.of());
        var activity = window(4, 2, 0, null, "Activity", "com.example", root, List.of(9));

        var snapshot = new AccessibilitySnapshotCollector().collect(
                List.of(dialog, activity),
                new AccessibilitySnapshotCollector.Request(
                        CaptureScope.GLOBAL, null, "device", 100, 10));

        assertEquals(List.of(2, 7), snapshot.windows().stream().map(w -> w.layer()).toList());
        assertEquals("a:w:4", snapshot.windows().get(1).parentWindowId());
        assertEquals(List.of("a:w:9"), snapshot.windows().get(0).childWindowIds());
    }

    @Test
    public void targetScopeFiltersPackagesAndCreatesEightPropertyGroups() {
        var targetRoot = node("com.target", "TextView", Map.of(
                "class", "android.widget.TextView",
                "boundsInScreen", "[0,0][80,40]",
                "alpha", "1.0",
                "text", "Hello",
                "clickable", "true",
                "contextClickable", "false",
                "screenReaderFocusable", "true",
                "actions", "ACTION_CLICK",
                "backend", "Accessibility"));
        var otherRoot = node("com.other", "Button", Map.of("class", "Button"));

        var snapshot = new AccessibilitySnapshotCollector().collect(
                List.of(
                        window(1, 1, 0, null, "Target", "com.target", targetRoot, List.of()),
                        window(2, 2, 0, null, "Other", "com.other", otherRoot, List.of())),
                new AccessibilitySnapshotCollector.Request(
                        CaptureScope.TARGET_PROCESS, "com.target", "device", 100, 10));

        assertEquals(1, snapshot.windows().size());
        var groups = snapshot.nodes().values().iterator().next().propertyGroups();
        assertEquals(List.of(
                        "identity", "windowHierarchy", "geometryLayout", "renderingTransform",
                        "content", "interactionState", "accessibility", "captureDiagnostics"),
                groups.stream().map(group -> group.name()).toList());
        assertFalse(groups.get(0).properties().isEmpty());
        assertEquals("0", groups.get(1).properties().get("depth").value());
        assertEquals("Hello", groups.get(4).properties().get("text").value());
        assertEquals("false", groups.get(5).properties().get("contextClickable").value());
        assertEquals("true", groups.get(6).properties().get("screenReaderFocusable").value());
    }

    @Test
    public void reportsDeterministicNodeTruncation() {
        var grandchild = node("com.example", "TextView", Map.of());
        var child = node("com.example", "LinearLayout", Map.of(), grandchild);
        var root = node("com.example", "FrameLayout", Map.of(), child);

        var snapshot = new AccessibilitySnapshotCollector().collect(
                List.of(window(1, 1, 0, null, "Main", "com.example", root, List.of())),
                new AccessibilitySnapshotCollector.Request(
                        CaptureScope.GLOBAL, null, "device", 2, 10));

        assertEquals(2, snapshot.nodes().size());
        assertTrue(snapshot.diagnostics().stream().anyMatch(d -> d.code().equals("node_limit")));
    }

    private static FakeWindow window(
            int id,
            int layer,
            int displayId,
            Integer parentId,
            String title,
            String packageName,
            FakeNode root,
            List<Integer> childIds) {
        return new FakeWindow(id, layer, displayId, parentId, title, packageName, root, childIds);
    }

    private static FakeNode node(String packageName, String className, Map<String, String> properties, FakeNode... children) {
        return new FakeNode(packageName, className, properties, List.of(children));
    }

    private record FakeWindow(
            int id,
            int layer,
            int displayId,
            Integer parentId,
            String title,
            String packageName,
            FakeNode root,
            List<Integer> childIds) implements AccessibilitySnapshotCollector.WindowData {
        @Override public String type() { return "APPLICATION"; }
        @Override public Rect bounds() { return new Rect(0, 0, 320, 640); }
        @Override public boolean active() { return true; }
        @Override public boolean focused() { return true; }
    }

    private record FakeNode(
            String packageName,
            String className,
            Map<String, String> properties,
            List<FakeNode> children) implements AccessibilitySnapshotCollector.NodeData {
        @Override public String resourceId() { return null; }
        @Override public String text() { return properties.get("text"); }
        @Override public String contentDescription() { return null; }
        @Override public Rect bounds() { return new Rect(0, 0, 80, 40); }
        @Override public Rect visibleBounds() { return bounds(); }
        @Override public Integer drawingOrder() { return null; }
    }
}
