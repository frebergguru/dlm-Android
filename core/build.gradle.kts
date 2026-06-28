import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

android {
    namespace = "guru.freberg.dlm.core"
    compileSdk = 37
    ndkVersion = "30.0.14904198"

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        externalNativeBuild {
            cmake {
                // C11 for libdlm; the JNI bridge stays C as well.
                arguments += "-DANDROID_STL=c++_shared"
                cFlags += listOf("-std=c11", "-Wall", "-Wextra")
            }
        }

        ndk {
            // Ship 64-bit + 32-bit ARM for devices, x86_64 for the emulator/CI.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
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

    // Prebuilt native dependency .so/.a + headers produced by scripts/build-deps.sh
    // live under ../nativeDeps and are referenced from CMakeLists.txt.
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.coroutines.android)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.espresso.core)
}
