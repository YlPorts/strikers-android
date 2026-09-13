import java.net.URI

plugins {
    id("com.android.application")
    kotlin("android")
}

kotlin {
    jvmToolchain(17)
}

val sdlVersion = "3.4.10"
val generatedSDLJava = layout.buildDirectory.dir("generated/sdl-java")
val sdlArchive = layout.buildDirectory.file("downloads/SDL-$sdlVersion.tar.gz")

val prepareSDLJava by tasks.registering {
    outputs.dir(generatedSDLJava)

    doLast {
        val archive = sdlArchive.get().asFile
        archive.parentFile.mkdirs()

        if (!archive.exists()) {
            URI("https://github.com/libsdl-org/SDL/archive/refs/tags/release-$sdlVersion.tar.gz")
                .toURL()
                .openStream()
                .use { input ->
                    archive.outputStream().use { output -> input.copyTo(output) }
                }
        }

        val destination = generatedSDLJava.get().asFile
        destination.deleteRecursively()
        destination.mkdirs()

        copy {
            from(tarTree(resources.gzip(archive)))
            include("SDL-release-$sdlVersion/android-project/app/src/main/java/**")
            eachFile {
                path = path.substringAfter("SDL-release-$sdlVersion/android-project/app/src/main/java/")
            }
            includeEmptyDirs = false
            into(destination)
        }
    }
}

android {
    namespace = "dev.ylports.strikers"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.ylports.strikers"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0-dev"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DSTRIKERS_FFMPEG=OFF",
                    "-DAURORA_SDL3_PROVIDER=vendor",
                    "-DAURORA_SDL3_LINKAGE=shared",
                    "-DAURORA_DAWN_PROVIDER=package",
                    "-DAURORA_DAWN_LINKAGE=static",
                    "-DAURORA_CACHE_USE_ZSTD=OFF"
                )
                cppFlags += listOf("-std=c++20")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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

    sourceSets {
        getByName("main") {
            java.srcDir(generatedSDLJava)
            java.srcDir("../../smstrikers-port/extern/aurora/platforms/android/java")
        }
    }

    ndkVersion = "27.2.12479018"
}

tasks.matching {
    it.name.startsWith("compile") &&
        (it.name.endsWith("JavaWithJavac") || it.name.endsWith("Kotlin"))
}.configureEach {
    dependsOn(prepareSDLJava)
}
