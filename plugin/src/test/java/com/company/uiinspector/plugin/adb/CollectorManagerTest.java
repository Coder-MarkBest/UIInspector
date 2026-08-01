package com.company.uiinspector.plugin.adb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CollectorManagerTest {
    @Test
    void installsWithArgumentArrayAndOpensAccessibilitySettings() throws Exception {
        var adb = new RecordingAdb();
        var manager = new CollectorManager(adb, Path.of("payload", "collector.apk"));

        manager.install("serial one");
        manager.openAccessibilitySettings("serial one");

        assertEquals(List.of("-s", "serial one", "shell", "dumpsys", "package",
                        CollectorManager.PACKAGE_NAME), adb.commands.get(0));
        assertEquals(List.of("-s", "serial one", "install", "-r", "-t", "payload/collector.apk"),
                adb.commands.get(1));
        assertEquals(List.of("-s", "serial one", "shell", "am", "start", "-a",
                        "android.settings.ACCESSIBILITY_SETTINGS"), adb.commands.get(2));
    }

    @Test
    void doesNotReinstallCurrentCollectorAndRestartItsAccessibilityService() throws Exception {
        var adb = new RecordingAdb("versionCode=1 minSdk=28 targetSdk=36");
        var manager = new CollectorManager(adb, Path.of("payload", "collector.apk"));

        manager.install("emulator-5554");

        assertEquals(List.of(List.of("-s", "emulator-5554", "shell", "dumpsys", "package",
                CollectorManager.PACKAGE_NAME)), adb.commands);
    }

    private static final class RecordingAdb implements AdbClient {
        final List<List<String>> commands = new ArrayList<>();
        final String packageDump;

        RecordingAdb() {
            this("");
        }

        RecordingAdb(String packageDump) {
            this.packageDump = packageDump;
        }

        @Override public CommandResult run(List<String> arguments, Duration timeout) {
            commands.add(List.copyOf(arguments));
            String output = arguments.contains("dumpsys") ? packageDump : "Success";
            return new CommandResult(0, output, "", output.getBytes());
        }
    }
}
