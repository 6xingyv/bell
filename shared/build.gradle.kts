// SPDX-License-Identifier: LicenseRef-Mocha-1.0
import java.io.File

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
    id("com.android.kotlin.multiplatform.library")
}

tasks.withType<Test>().configureEach {
    val nativeDir =
        providers.gradleProperty("desktopAudioDir").orElse("vendor/foundation-audio").get()
    val audioDir = rootProject.file(nativeDir)
    jvmArgs(
        "-Djna.library.path=${audioDir.resolve("zig-out/bin")}${File.pathSeparator}${audioDir.resolve("ffmpeg-windows-shared/bin")}",
        "-Djellify.cacheDir=${temporaryDir.resolve("media")}",
    )
    environment(
        "PATH",
        audioDir.resolve("ffmpeg-windows-shared/bin").path +
            File.pathSeparator +
            System.getenv("PATH"),
    )
}

kotlin {
    jvmToolchain(21)
    android {
        namespace = "com.mocharealm.bell.shared"
        compileSdk = 37
        minSdk = 33
        androidResources.enable = true
    }
    jvm()
    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.animation)
            implementation(compose.material3)
            implementation(compose.components.resources)
            implementation("sh.calvin.reorderable:reorderable:3.1.0")
            api("io.ktor:ktor-client-core:3.6.1")
            implementation("io.ktor:ktor-client-content-negotiation:3.6.1")
            implementation("io.ktor:ktor-client-websockets:3.6.1")
            implementation("io.ktor:ktor-serialization-kotlinx-json:3.6.1")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
            implementation("io.insert-koin:koin-core:4.2.2")
            implementation("io.coil-kt.coil3:coil-compose:3.6.3")
            implementation("io.coil-kt.coil3:coil-network-ktor3:3.6.3")
            api("com.mocharealm.accompanist:lyrics-core:0.5.1")
            api("com.mocharealm.accompanist:lyrics-ui:2.0.0-rc.4")
            implementation("com.mocharealm.accompanist:lyrics-phonetics-runtime:0.1.0")
            implementation("com.mocharealm.accompanist:lyrics-phonetics-data-mandarin:0.1.0")
            implementation("com.mocharealm.accompanist:lyrics-phonetics-data-cantonese:0.1.0")
            implementation("com.mocharealm.accompanist:lyrics-phonetics-data-japanese:0.1.0")
        }
        androidMain {
            kotlin.srcDir("src/jvmSharedMain/kotlin")
            kotlin.srcDir(
                rootProject
                    .file(
                        providers
                            .gradleProperty("foundationAudioDir")
                            .getOrElse("vendor/foundation-audio")
                    )
                    .resolve("android/app/src/main/java/com/mocharealm/foundation/audio")
            )
            kotlin.exclude("**/MusicAnalysis.kt")
            dependencies {
                implementation("io.ktor:ktor-client-okhttp:3.6.1")
                implementation(project(":glasmio-scaffold"))
                implementation("androidx.activity:activity-compose:1.13.0")
            }
        }
        jvmMain { kotlin.srcDir("src/jvmSharedMain/kotlin") }
        jvmMain.dependencies {
            implementation("com.ibm.icu:icu4j:79.1")
            implementation("io.ktor:ktor-client-cio:3.6.1")
            implementation("net.java.dev.jna:jna:5.19.1")
            implementation("net.java.dev.jna:jna-platform:5.19.1")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("io.ktor:ktor-client-mock:3.6.1")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "com.mocharealm.bell.resources"
}
