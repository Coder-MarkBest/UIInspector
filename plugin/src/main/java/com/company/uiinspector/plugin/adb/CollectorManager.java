package com.company.uiinspector.plugin.adb;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

public final class CollectorManager {
    public static final String PACKAGE_NAME = "com.company.uiinspector.collector";
    private static final int VERSION_CODE = 2;
    private static final Pattern CURRENT_VERSION = Pattern.compile("\\bversionCode=" + VERSION_CODE + "\\b");
    private static final Duration INSTALL_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(20);

    private final AdbClient adb;
    private final Path collectorApk;

    public CollectorManager(AdbClient adb, Path collectorApk) {
        this.adb = adb;
        this.collectorApk = collectorApk;
    }

    /**
     * Ensures the collector APK is installed at the required version.
     * @return true if a fresh install or upgrade was performed.
     */
    public boolean install(String serial) throws IOException, InterruptedException {
        CommandResult installed = adb.run(List.of(
                "-s", serial, "shell", "dumpsys", "package", PACKAGE_NAME), COMMAND_TIMEOUT);
        if (installed.exitCode() == 0 && CURRENT_VERSION.matcher(installed.stdout()).find()) {
            return false;
        }
        adb.run(List.of("-s", serial, "install", "-r", "-t", collectorApk.toString()), INSTALL_TIMEOUT)
                .requireSuccess("Collector installation");
        return true;
    }

    public void openAccessibilitySettings(String serial) throws IOException, InterruptedException {
        adb.run(List.of(
                        "-s", serial, "shell", "am", "start", "-a",
                        "android.settings.ACCESSIBILITY_SETTINGS"), COMMAND_TIMEOUT)
                .requireSuccess("Opening Accessibility settings");
    }
}
