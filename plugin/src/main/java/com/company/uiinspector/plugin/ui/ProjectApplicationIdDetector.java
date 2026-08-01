package com.company.uiinspector.plugin.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ProjectApplicationIdDetector {
    private static final Pattern APPLICATION_ID = Pattern.compile(
            "\\bapplicationId\\s*(?:=\\s*)?[\\\"']([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+)[\\\"']");

    private ProjectApplicationIdDetector() {}

    public static Optional<String> detect(Path projectRoot) {
        if (projectRoot == null || !Files.isDirectory(projectRoot)) return Optional.empty();
        try (var paths = Files.walk(projectRoot, 5)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals("build.gradle")
                            || path.getFileName().toString().equals("build.gradle.kts"))
                    .sorted()
                    .map(ProjectApplicationIdDetector::readApplicationId)
                    .flatMap(Optional::stream)
                    .findFirst();
        } catch (IOException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<String> readApplicationId(Path path) {
        try {
            Matcher matcher = APPLICATION_ID.matcher(Files.readString(path));
            return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
        } catch (IOException ignored) {
            return Optional.empty();
        }
    }
}
