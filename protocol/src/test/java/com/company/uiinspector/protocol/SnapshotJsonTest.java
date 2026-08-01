package com.company.uiinspector.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SnapshotJsonTest {
    @Test
    void roundTripsWindowHierarchyViewHierarchyAndGroupedProperties() {
        var identity = new LinkedHashMap<String, PropertyValue>();
        var editor = new PropertyEditor(
                EditorTarget.VIEW, EditorValueType.BOOLEAN, "setter:setEnabled", List.of());
        identity.put("enabled", new PropertyValue("true", "JVMTI", editor, null));
        identity.put("class", new PropertyValue("android.widget.TextView", "JVMTI"));
        var node = new UiNode(
                "v:1", CaptureSource.JVMTI, "w:child", null, List.of(), 0,
                "android.widget.TextView", "com.example:id/title", "Title", "Page title",
                new Rect(12, 24, 112, 64), new Rect(12, 24, 112, 64), 0,
                List.of(new PropertyGroup("identity", identity)));
        var parent = new WindowSnapshot(
                "w:parent", CaptureSource.SEMANTIC, "com.example", "com.example", 0,
                "APPLICATION", 3, null, List.of("w:child"), new Rect(0, 0, 320, 640),
                false, false, "Main", null, List.of());
        var child = new WindowSnapshot(
                "w:child", CaptureSource.JVMTI, "com.example", "com.example", 0,
                "APPLICATION_PANEL", 5, "w:parent", List.of(), new Rect(0, 0, 320, 80),
                true, true, "Panel", "v:1", List.of());
        var snapshot = new InspectionSnapshot(
                1, 1_722_480_000_000L, "serial-1", CaptureScope.TARGET_PROCESS,
                "com.example", "com.example", List.of(), List.of(parent, child),
                Map.of(node.id(), node), List.of(new Diagnostic("INFO", "capture", "ok")), null);

        var decoded = SnapshotJson.decode(SnapshotJson.encode(snapshot));

        assertEquals(1, decoded.schemaVersion());
        assertEquals("w:parent", decoded.windows().get(1).parentWindowId());
        assertEquals(List.of("w:child"), decoded.windows().get(0).childWindowIds());
        assertEquals("JVMTI", decoded.nodes().get("v:1").propertyGroups().get(0)
                .properties().get("class").source());
        assertEquals(EditorValueType.BOOLEAN, decoded.nodes().get("v:1").propertyGroups().get(0)
                .properties().get("enabled").editor().valueType());
        assertNull(decoded.nodes().get("v:1").propertyGroups().get(0)
                .properties().get("class").editor());
        assertNull(decoded.screenshot());
    }

    @Test
    void rejectsUnknownSchemaVersion() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> SnapshotJson.decode("{\"schemaVersion\":2}"));
        assertEquals("Unsupported snapshot schema version: 2", error.getMessage());
    }
}
