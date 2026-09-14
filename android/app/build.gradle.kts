import java.net.URI
import org.gradle.api.tasks.Sync

plugins {
    id("com.android.application")
}

val sdlVersion = "3.4.10"
val sdlArchive = layout.buildDirectory.file("downloads/SDL-$sdlVersion.tar.gz")
val sdlJavaDir = layout.buildDirectory.dir("generated/sdl-java")

val downloadSdlSource = tasks.register("downloadSdlSource") {
    outputs.file(sdlArchive)
    doLast {
        val target = sdlArchive.get().asFile
        if (!target.exists()) {
            target.parentFile.mkdirs()
            URI("https://github.com/libsdl-org/SDL/archive/refs/tags/release-$sdlVersion.tar.gz")
                .toURL()
                .openStream()
                .use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
        }
    }
}

val prepareSdlJava = tasks.register<Sync>("prepareSdlJava") {
    dependsOn(downloadSdlSource)
    from({ tarTree(resources.gzip(sdlArchive.get().asFile)) }) {
        include("SDL-release-$sdlVersion/android-project/app/src/main/java/**")
        eachFile {
            path = path.substringAfter("android-project/app/src/main/java/")
        }
        includeEmptyDirs = false
    }
    into(sdlJavaDir)
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
        versionName = "0.2.0-sdl-dev"

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

    sourceSets {
        getByName("main") {
            // SDL's Android Java glue must match the exact SDL revision linked
            // statically into libstrikers.so by Aurora.
            java.srcDir(sdlJavaDir)
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

tasks.named("preBuild") {
    dependsOn(prepareSdlJava)
}
