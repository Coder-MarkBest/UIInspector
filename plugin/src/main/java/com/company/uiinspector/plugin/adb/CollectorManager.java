package com.company.uiinspector.plugin.adb;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

public final class CollectorManager {
    public static final String PACKAGE_NAME = "com.company.uiinspector.collector";
    private static final int VERSION_CODE = 3;
    private static final Pattern CURRENT_VERSION = Pattern.compile("\\bversionCode=" + VERSION_CODE + "\\b");
    private static final Duration INSTALL_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration VERIFY_TTL = Duration.ofSeconds(30);

    private final AdbClient adb;
    private final Path collectorApk;
    private final java.util.Map<String, Long> verifiedAt = new java.util.concurrent.ConcurrentHashMap<>();

    public CollectorManager(AdbClient adb, Path collectorApk) {
        this.adb = adb;
        this.collectorApk = collectorApk;
    }

    /**
     * Ensures the collector APK is installed at the required version.
     * @return true if a fresh install or upgrade was performed.
     */
    public boolean install(String serial) throws IOException, InterruptedException {
        Long lastVerified = verifiedAt.get(serial);
        if (lastVerified != null && System.nanoTime() - lastVerified < VERIFY_TTL.toNanos()) {
            return false;
        }
        CommandResult installed = adb.run(List.of(
                "-s", serial, "shell", "dumpsys", "package", PACKAGE_NAME), COMMAND_TIMEOUT);
        if (installed.exitCode() == 0 && CURRENT_VERSION.matcher(installed.stdout()).find()) {
            verifiedAt.put(serial, System.nanoTime());
            return false;
        }
        adb.run(List.of("-s", serial, "install", "-r", "-t", collectorApk.toString().replace('\\', '/')), INSTALL_TIMEOUT)
                .requireSuccess("Collector installation");
        verifiedAt.put(serial, System.nanoTime());
        return true;
    }

    public void openAccessibilitySettings(String serial) throws IOException, InterruptedException {
        adb.run(List.of(
                        "-s", serial, "shell", "am", "start", "-a",
                        "android.settings.ACCESSIBILITY_SETTINGS"), COMMAND_TIMEOUT)
                .requireSuccess("Opening Accessibility settings");
    }
}
