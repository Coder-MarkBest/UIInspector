import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    alias(libs.plugins.intellij.platform)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    implementation(project(":protocol"))
    intellijPlatform {
        androidStudio("2026.1.2.11")
    }
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

intellijPlatform {
    pluginConfiguration {
        name = "Android UI Inspector"
        version = project.version.toString()
        description = "Inspect Android Window and View hierarchies in a local browser without target-project integration."
        ideaVersion {
            sinceBuild = "261"
            untilBuild = "261.*"
        }
        vendor {
            name = "Coder-MarkBest"
        }
    }
    pluginVerification {
        ides {
            create(IntelliJPlatformType.AndroidStudio, "2026.1.2.11")
        }
    }
}

tasks.processResources {
    dependsOn(":collector:assembleDebug", ":agent-helper:assembleRelease", ":agent-native:assembleRelease")
    from(project(":collector").layout.buildDirectory.dir("outputs/apk/debug")) {
        include("collector-debug.apk")
        into("payload")
        rename { "collector.apk" }
    }
    from(project(":agent-helper").layout.buildDirectory.dir("outputs/apk/release")) {
        include("agent-helper-release-unsigned.apk")
        into("payload")
        rename { "agent-helper.apk" }
    }
    from(project(":agent-native").layout.buildDirectory.dir("outputs/aar")) {
        include("agent-native-release.aar")
        into("payload")
        rename { "agent-native.aar" }
    }
}

tasks.test {
    useJUnitPlatform()
}
