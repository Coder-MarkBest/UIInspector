plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.company.uiinspector.collector"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.company.uiinspector.collector"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
}

dependencies {
    implementation(project(":protocol"))
    testImplementation(libs.junit4)
}

