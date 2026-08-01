package com.company.uiinspector.plugin.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.uiinspector.plugin.adb.AdbClient;
import com.company.uiinspector.plugin.adb.CommandResult;
import com.company.uiinspector.plugin.capture.AgentDeployer;
import com.company.uiinspector.plugin.capture.AgentPayloads;
import com.company.uiinspector.protocol.CaptureScope;
import com.company.uiinspector.protocol.CaptureSource;
import com.company.uiinspector.protocol.EditorTarget;
import com.company.uiinspector.protocol.EditorValueType;
import com.company.uiinspector.protocol.InspectionSnapshot;
import com.company.uiinspector.protocol.MutationCommand;
import com.company.uiinspector.protocol.MutationJson;
import com.company.uiinspector.protocol.MutationResult;
import com.company.uiinspector.protocol.PropertyEditor;
import com.company.uiinspector.protocol.PropertyGroup;
import com.company.uiinspector.protocol.PropertyValue;
import com.company.uiinspector.protocol.Rect;
import com.company.uiinspector.protocol.UiNode;
import com.company.uiinspector.protocol.WindowSnapshot;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InspectorApiTest {
    @Test
    void devicesExposeWhetherEachAdbStateIsReadyForCapture() {
        AdbClient adb = new AdbClient() {
            @Override
            public CommandResult run(List<String> arguments, Duration timeout) {
                String output = """
                        List of devices attached
                        emulator-5554 device product:sdk model:Pixel_Tablet device:emu64a
                        serial-two unauthorized usb:1-1
                        """;
                return new CommandResult(0, output, "", output.getBytes());
            }
        };
        var api = new InspectorApi(adb, null, null, null);

        var devices = JsonParser.parseString(api.devices()).getAsJsonArray();

        assertTrue(devices.get(0).getAsJsonObject().get("ready").getAsBoolean());
        assertFalse(devices.get(1).getAsJsonObject().get("ready").getAsBoolean());
    }

    @Test
    void rejectsGlobalSemanticAndMissingEditorTargets() {
        var adb = new MutationAdb(true, 123, mutationResult(editableSnapshot(
                CaptureScope.TARGET_PROCESS, CaptureSource.JVMTI, true, "false")));

        assertEquals("target_read_only", code(api(adb, editableSnapshot(
                CaptureScope.GLOBAL, CaptureSource.JVMTI, true, "true")).mutate(request(), Map.of())));
        assertEquals("target_read_only", code(api(adb, editableSnapshot(
                CaptureScope.TARGET_PROCESS, CaptureSource.SEMANTIC, true, "true")).mutate(request(), Map.of())));
        assertEquals("property_read_only", code(api(adb, editableSnapshot(
                CaptureScope.TARGET_PROCESS, CaptureSource.JVMTI, false, "true")).mutate(request(), Map.of())));
    }

    @Test
    void rejectsStaleAndNonDebuggableProcesses() {
        var staleAdb = new MutationAdb(true, 999, mutationResult(editableSnapshot(
                CaptureScope.TARGET_PROCESS, CaptureSource.JVMTI, true, "false")));
        assertEquals("stale_target", code(api(staleAdb, editableSnapshot(
                CaptureScope.TARGET_PROCESS, CaptureSource.JVMTI, true, "true")).mutate(request(), Map.of())));

        var releaseAdb = new MutationAdb(false, 123, mutationResult(editableSnapshot(
                CaptureScope.TARGET_PROCESS, CaptureSource.JVMTI, true, "false")));
        assertEquals("target_not_debuggable", code(api(releaseAdb, editableSnapshot(
                CaptureScope.TARGET_PROCESS, CaptureSource.JVMTI, true, "true")).mutate(request(), Map.of())));
    }

    @Test
    void derivesCommandFromCurrentEditorAndReplacesCurrentWithFreshSnapshot() {
        var fresh = editableSnapshot(CaptureScope.TARGET_PROCESS, CaptureSource.JVMTI, true, "false");
        var adb = new MutationAdb(true, 123, mutationResult(fresh));
        var api = api(adb, editableSnapshot(
                CaptureScope.TARGET_PROCESS, CaptureSource.JVMTI, true, "true"));

        var result = MutationJson.decodeResult(api.mutate(request(), Map.of()));

        assertTrue(result.success());
        assertEquals("v:n:0:0", adb.command.targetId());
        assertEquals("enabled", adb.command.propertyName());
        assertEquals("setter:setEnabled", adb.command.adapter());
        assertEquals(EditorValueType.BOOLEAN, adb.command.valueType());
        assertEquals("false", adb.command.value());
        assertNotNull(result.snapshot().screenshot());
        assertEquals("false", result.snapshot().nodes().get("v:n:0:0")
                .propertyGroups().get(0).properties().get("enabled").value());
        var session = JsonParser.parseString(api.session()).getAsJsonObject();
        assertEquals("false", session.getAsJsonObject("snapshot")
                .getAsJsonObject("nodes").getAsJsonObject("v:n:0:0")
                .getAsJsonArray("propertyGroups").get(0).getAsJsonObject()
                .getAsJsonObject("properties").getAsJsonObject("enabled").get("value").getAsString());
    }

    private static InspectorApi api(MutationAdb adb, InspectionSnapshot initial) {
        var deployer = new AgentDeployer(
                adb,
                new AgentPayloads(Path.of("helper.apk"), Map.of("x86_64", Path.of("agent.so"))),
                () -> "mutation-1");
        return new InspectorApi(adb, null, deployer, null, initial);
    }

    private static String code(String json) {
        return JsonParser.parseString(json).getAsJsonObject().get("code").getAsString();
    }

    private static String request() {
        return """
                {
                  "serial":"emulator-5554",
                  "pid":123,
                  "packageName":"com.example",
                  "processName":"com.example",
                  "targetId":"v:n:0:0",
                  "expectedClassName":"android.widget.TextView",
                  "expectedResourceId":"com.example:id/title",
                  "propertyName":"enabled",
                  "value":"false"
                }
                """;
    }

    private static String mutationResult(InspectionSnapshot snapshot) {
        return MutationJson.encodeResult(new MutationResult(
                1, true, "ok", "Applied", "true", "false", snapshot));
    }

    private static InspectionSnapshot editableSnapshot(
            CaptureScope scope,
            CaptureSource source,
            boolean editable,
            String value) {
        var properties = new LinkedHashMap<String, PropertyValue>();
        var editor = editable
                ? new PropertyEditor(EditorTarget.VIEW, EditorValueType.BOOLEAN,
                        "setter:setEnabled", List.of())
                : null;
        properties.put("enabled", new PropertyValue(value, source.name(), editor, null));
        var node = new UiNode(
                "v:n:0:0", source, "v:w:0", null, List.of(), 0,
                "android.widget.TextView", "com.example:id/title", "Title", null,
                new Rect(0, 0, 100, 40), new Rect(0, 0, 100, 40), 0,
                List.of(new PropertyGroup("identity", properties)));
        var window = new WindowSnapshot(
                "v:w:0", source, "com.example", "com.example", 0,
                "APPLICATION", 0, null, List.of(), new Rect(0, 0, 100, 40),
                true, true, "Main", node.id(), List.of());
        return new InspectionSnapshot(
                1, 20L, "emulator-5554", scope, "com.example", "com.example",
                List.of(), List.of(window), Map.of(node.id(), node), List.of(), null);
    }

    private static final class MutationAdb implements AdbClient {
        private final boolean debuggable;
        private final int runningPid;
        private final String result;
        private final List<List<String>> commands = new ArrayList<>();
        private MutationCommand command;

        private MutationAdb(boolean debuggable, int runningPid, String result) {
            this.debuggable = debuggable;
            this.runningPid = runningPid;
            this.result = result;
        }

        @Override
        public CommandResult run(List<String> arguments, Duration timeout) throws IOException {
            commands.add(List.copyOf(arguments));
            String output = "";
            byte[] bytes = new byte[0];
            if (arguments.contains("ps")) {
                output = "USER PID PPID VSZ RSS WCHAN ADDR S NAME\n"
                        + "u0_a1 " + runningPid + " 1 1 1 0 0 S com.example\n";
            } else if (arguments.contains("dumpsys") && arguments.contains("package")) {
                output = debuggable ? "flags=[ DEBUGGABLE ]" : "flags=[ HAS_CODE ]";
            } else if (arguments.contains("getprop")) {
                output = "x86_64\n";
            } else if (arguments.contains("pwd")) {
                output = "/data/user/0/com.example\n";
            } else if (arguments.contains("push")
                    && arguments.get(arguments.size() - 1).endsWith("-command.json")) {
                command = MutationJson.decodeCommand(Files.readString(
                        Path.of(arguments.get(arguments.size() - 2))));
            } else if (arguments.contains("cat")
                    && arguments.stream().anyMatch(value -> value.contains("mutation-result"))) {
                output = result;
            } else if (arguments.contains("screencap")) {
                bytes = new byte[] {(byte) 0x89, 'P', 'N', 'G'};
            }
            if (bytes.length == 0) bytes = output.getBytes();
            return new CommandResult(0, output, "", bytes);
        }
    }
}
