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
                .use { input -> target.outputStream().use { output -> input.copyTo(output) } }
        }
    }
}

val prepareSdlJava = tasks.register<Sync>("prepareSdlJava") {
    dependsOn(downloadSdlSource)
    from({ tarTree(resources.gzip(sdlArchive.get().asFile)) }) {
        include("SDL-release-$sdlVersion/android-project/app/src/main/java/**")
        eachFile { path = path.substringAfter("android-project/app/src/main/java/") }
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
        versionCode = 103
        versionName = "1.0.3"

        ndk { abiFilters += listOf("arm64-v8a") }

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
                cppFlags += listOf("-std=c++17")
                targets += listOf("SDL3-shared", "strikers_diag", "strikers_android")
            }
        }
    }

    buildTypes {
        debug { isJniDebuggable = true }
        release {
            isDebuggable = false
            isJniDebuggable = false
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.1"
        }
    }

    sourceSets { getByName("main") { java.srcDir(sdlJavaDir.get().asFile) } }
    packaging { jniLibs { useLegacyPackaging = false } }
}

tasks.named("preBuild") { dependsOn(prepareSdlJava) }
