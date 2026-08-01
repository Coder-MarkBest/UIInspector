package com.company.uiinspector.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MutationJsonTest {
    @Test
    void roundTripsCommandAndSuccessResult() {
        var command = new MutationCommand(
                1, "v:n:0:0.1", "android.widget.TextView", "com.example:id/title",
                "text", "setter:setText", EditorTarget.VIEW, EditorValueType.STRING, "Changed");

        assertEquals("Changed", MutationJson.decodeCommand(MutationJson.encodeCommand(command)).value());

        var result = new MutationResult(
                1, true, "ok", "Applied", "Before", "Changed", snapshot());
        var decoded = MutationJson.decodeResult(MutationJson.encodeResult(result));

        assertTrue(decoded.success());
        assertEquals("Before", decoded.previousValue());
        assertEquals("Changed", decoded.appliedValue());
    }

    @Test
    void rejectsUnknownMutationSchema() {
        assertThrows(IllegalArgumentException.class,
                () -> MutationJson.decodeCommand("{\"schemaVersion\":2}"));
    }

    private static InspectionSnapshot snapshot() {
        return new InspectionSnapshot(
                1, 1_722_480_000_000L, "serial-1", CaptureScope.TARGET_PROCESS,
                "com.example", "com.example", List.of(), List.of(), Map.of(), List.of(), null);
    }
}
