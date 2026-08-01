plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.company.uiinspector.agent"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.company.uiinspector.agenthelper"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":protocol"))
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
}

