package com.company.uiinspector.plugin.adb;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class AdbLocator {
    private AdbLocator() {}

    public static Optional<Path> locate(Path projectSdk, Map<String, String> environment, List<Path> pathEntries) {
        String executable = System.getProperty("os.name", "").toLowerCase().contains("win") ? "adb.exe" : "adb";
        List<Path> candidates = new ArrayList<>();
        if (projectSdk != null) candidates.add(projectSdk.resolve("platform-tools").resolve(executable));
        addSdk(candidates, environment.get("ANDROID_HOME"), executable);
        addSdk(candidates, environment.get("ANDROID_SDK_ROOT"), executable);
        pathEntries.forEach(path -> candidates.add(path.resolve(executable)));
        return candidates.stream().filter(Files::isRegularFile).findFirst();
    }

    private static void addSdk(List<Path> candidates, String root, String executable) {
        if (root != null && !root.isBlank()) {
            candidates.add(Path.of(root).resolve("platform-tools").resolve(executable));
        }
    }
}
