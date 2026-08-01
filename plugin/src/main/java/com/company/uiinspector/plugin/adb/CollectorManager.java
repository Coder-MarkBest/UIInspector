package com.company.uiinspector.plugin.adb;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public final class CollectorManager {
    public static final String PACKAGE_NAME = "com.company.uiinspector.collector";
    private static final Duration INSTALL_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(20);

    private final AdbClient adb;
    private final Path collectorApk;

    public CollectorManager(AdbClient adb, Path collectorApk) {
        this.adb = adb;
        this.collectorApk = collectorApk;
    }

    public void install(String serial) throws IOException, InterruptedException {
        adb.run(List.of("-s", serial, "install", "-r", "-t", collectorApk.toString()), INSTALL_TIMEOUT)
                .requireSuccess("Collector installation");
    }

    public void openAccessibilitySettings(String serial) throws IOException, InterruptedException {
        adb.run(List.of(
                        "-s", serial, "shell", "am", "start", "-a",
                        "android.settings.ACCESSIBILITY_SETTINGS"), COMMAND_TIMEOUT)
                .requireSuccess("Opening Accessibility settings");
    }
}
