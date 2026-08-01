package com.company.uiinspector.plugin.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.uiinspector.plugin.adb.AdbClient;
import com.company.uiinspector.plugin.adb.CommandResult;
import com.google.gson.JsonParser;
import java.time.Duration;
import java.util.List;
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
}
