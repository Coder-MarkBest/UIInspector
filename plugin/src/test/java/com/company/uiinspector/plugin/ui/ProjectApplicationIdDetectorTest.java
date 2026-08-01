package com.company.uiinspector.plugin.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectApplicationIdDetectorTest {
    @TempDir Path project;

    @Test
    void findsApplicationIdWithoutModifyingTheBusinessProject() throws Exception {
        Path app = Files.createDirectories(project.resolve("vehicle-app"));
        Files.writeString(app.resolve("build.gradle.kts"), """
                plugins { id("com.android.application") }
                android { defaultConfig { applicationId = "com.example.vehicle" } }
                """);

        assertEquals("com.example.vehicle", ProjectApplicationIdDetector.detect(project).orElseThrow());
    }
}
