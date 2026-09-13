plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "dev.ylports.strikers"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.ylports.strikers"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-dev"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DSTRIKERS_ANDROID_DIAGNOSTIC_BUILD=ON",
                    "-DSTRIKERS_FFMPEG=OFF"
                )
                cppFlags += listOf("-std=c++20")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.1"
        }
    }

    ndkVersion = "27.2.12479018"
}
