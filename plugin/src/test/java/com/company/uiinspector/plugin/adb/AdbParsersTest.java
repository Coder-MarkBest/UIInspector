package com.company.uiinspector.plugin.adb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AdbParsersTest {
    @Test
    void parsesDeviceStateAndStableIdentityFromAdbDevices() {
        String output = """
                List of devices attached
                emulator-5554 device product:sdk_gphone model:Pixel_8 device:emu64 transport_id:1
                CAR001 unauthorized usb:1-2 transport_id:2

                """;

        var devices = AdbParsers.devices(output);

        assertEquals(2, devices.size());
        assertEquals("Pixel_8", devices.get(0).model());
        assertTrue(devices.get(0).ready());
        assertEquals("unauthorized", devices.get(1).state());
        assertFalse(devices.get(1).ready());
    }

    @Test
    void parsesAndroidPsWithoutAssumingFixedColumnWidths() {
        String output = """
                USER           PID  PPID     VSZ    RSS WCHAN            ADDR S NAME
                u0_a201      17342   801 1865432 125000 0                   0 S com.example.car
                u0_a201      17401   801 1865432  95000 0                   0 S com.example.car:remote
                system        2345   801 1200000 100000 0                   0 S com.android.systemui
                """;

        var processes = AdbParsers.processes(output);

        assertEquals(3, processes.size());
        assertEquals(17342, processes.get(0).pid());
        assertEquals("com.example.car", processes.get(1).packageName());
        assertEquals("com.example.car:remote", processes.get(1).processName());
        assertEquals("com.android.systemui", processes.get(2).packageName());
    }

    @Test
    void recognizesDebuggablePackageFlagsOnly() {
        assertTrue(AdbParsers.debuggable("flags=[ DEBUGGABLE HAS_CODE ALLOW_CLEAR_USER_DATA ]"));
        assertFalse(AdbParsers.debuggable("flags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ]"));
    }

    @Test
    void parsesOnlyPositiveJdwpProcessIds() {
        assertEquals(Set.of(410, 512), AdbParsers.debuggablePids("410\ninvalid\n0\n-3\n512\n"));
    }
}
