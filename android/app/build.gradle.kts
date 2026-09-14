plugins {
    id("com.android.application")
}

android {
    namespace = "com.ylports.strikers"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.ylports.strikers"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-dev"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
                cppFlags += listOf("-std=c++17")
                // Only package the Android shared library. The desktop executable
                // and dependency helper targets are not Android APK outputs.
                targets += listOf("strikers_android")
            }
        }
    }

    buildTypes {
        debug {
            isJniDebuggable = true
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

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}
