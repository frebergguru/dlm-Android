import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Producer module for the app's Baseline Profile. Runs an on-device macrobenchmark
// that exercises cold start + first scroll, emitting a profile the :app build bakes
// in via profileinstaller so those paths are AOT-compiled at install time.
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

android {
    namespace = "guru.freberg.dlm.baselineprofile"
    compileSdk = 37

    defaultConfig {
        // Baseline Profile generation requires API 28+ on the target device.
        minSdk = 28
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The app whose startup we profile.
    targetProjectPath = ":app"
}

// Generate against a physically connected device (000251566000166).
baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.junit)
    implementation(libs.espresso.core)
    implementation(libs.uiautomator)
    implementation(libs.benchmark.macro.junit4)
}
