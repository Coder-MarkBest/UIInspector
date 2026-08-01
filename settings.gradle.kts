pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "UIInspector"
include(":protocol")
include(":collector")
include(":plugin")
include(":agent-helper")
include(":agent-native")
include(":sample-target")
