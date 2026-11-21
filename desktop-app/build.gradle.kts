// SPDX-License-Identifier: LicenseRef-Mocha-1.0
import java.io.File

plugins {
    kotlin("jvm")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.components.resources)
    implementation("io.insert-koin:koin-core:4.2.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
}

compose.desktop { application { mainClass = "com.mocharealm.bell.desktop.MainKt" } }

tasks.withType<JavaExec>().configureEach {
    val audioDir =
        rootProject.file(
            providers.gradleProperty("desktopAudioDir").getOrElse("vendor/foundation-audio")
        )
    jvmArgs(
        "-Djna.library.path=${audioDir.resolve("zig-out/bin")}${File.pathSeparator}${audioDir.resolve("ffmpeg-windows-shared/bin")}"
    )
    environment(
        "PATH",
        audioDir.resolve("ffmpeg-windows-shared/bin").path +
            File.pathSeparator +
            System.getenv("PATH"),
    )
}
