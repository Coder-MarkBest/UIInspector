package com.company.uiinspector.plugin.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

    @Test
    void findsEveryDeclaredApplicationIdAcrossPluginDeclarationStyles() throws Exception {
        Path aliasedApp = Files.createDirectories(project.resolve("a-aliased-app"));
        Files.writeString(aliasedApp.resolve("build.gradle.kts"), """
                plugins { alias(libs.plugins.android.application) }
                android { defaultConfig { applicationId = "com.example.aliased" } }
                """);
        Path directApp = Files.createDirectories(project.resolve("b-direct-app"));
        Files.writeString(directApp.resolve("build.gradle"), """
                plugins { id 'com.android.application' }
                android { defaultConfig { applicationId 'com.example.direct' } }
                """);
        Path library = Files.createDirectories(project.resolve("c-library"));
        Files.writeString(library.resolve("build.gradle.kts"), """
                plugins { id("com.android.library") }
                android { namespace = "com.example.library" }
                """);

        assertEquals(
                List.of("com.example.aliased", "com.example.direct"),
                ProjectApplicationIdDetector.detectAll(project));
    }
}
