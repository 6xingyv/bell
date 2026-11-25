// SPDX-License-Identifier: LicenseRef-Mocha-1.0
plugins {
    kotlin("multiplatform") version "2.4.21" apply false
    kotlin("jvm") version "2.4.21" apply false
    kotlin("plugin.serialization") version "2.4.21" apply false
    kotlin("plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.compose") version "1.12.2" apply false
    id("com.android.application") version "9.5.1" apply false
    id("com.android.library") version "9.5.1" apply false
    id("com.android.kotlin.multiplatform.library") version "9.5.1" apply false
    id("com.diffplug.spotless") version "8.11.0"
}

subprojects {
    if (name.startsWith("glasmio-")) {
        // Keep vendored library build outputs under Bell's own build directory.
        layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("glasmio/${project.name}"))
        group = "io.github.neoruaa.glasmio"
        version = "0.1.3"
        plugins.withId("com.android.library") {
            extensions.configure<com.android.build.api.dsl.LibraryExtension> {
                compileSdk = 37
                defaultConfig {
                    minSdk = 33
                    ndk {
                        abiFilters +=
                            providers
                                .gradleProperty("bell.abis")
                                .orElse("arm64-v8a,armeabi-v7a,x86_64")
                                .get()
                                .split(",")
                    }
                }
                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_21
                    targetCompatibility = JavaVersion.VERSION_21
                }
                buildFeatures { compose = true }
            }
        }
    }
    if (name == "glasmio-glass")
        afterEvaluate {
            extensions.configure<com.android.build.api.dsl.LibraryExtension> {
                ndkVersion = "29.0.14206865"
            }
        }
}

spotless {
    kotlin {
        target("shared/src/**/*.kt", "android-app/src/**/*.kt", "desktop-app/src/**/*.kt")
        licenseHeader("// SPDX-License-Identifier: LicenseRef-Mocha-1.0")
        ktfmt("0.64").kotlinlangStyle()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target(
            "*.gradle.kts",
            "gradle/*.gradle.kts",
            "android-app/*.gradle.kts",
            "shared/*.gradle.kts",
            "desktop-app/*.gradle.kts",
        )
        licenseHeader(
            "// SPDX-License-Identifier: LicenseRef-Mocha-1.0",
            "^(plugins|pluginManagement|import)",
        )
        ktfmt("0.64").kotlinlangStyle()
        trimTrailingWhitespace()
        endWithNewline()
    }
    format("metadata") {
        target(
            ".github/**/*.yml",
            "gradle.properties",
            ".gitignore",
            ".editorconfig",
            "android-app/*.pro",
        )
        trimTrailingWhitespace()
        endWithNewline()
    }
}

tasks.named("check") {
    dependsOn(":shared:jvmTest", ":android-app:lintPerformance")
}

val bellSourceFiles =
    fileTree(rootDir) {
        include(
            "android-app/src/**/*.kt",
            "android-app/src/**/*.java",
            "android-app/src/**/*.xml",
            "shared/src/**/*.kt",
            "shared/src/**/*.java",
            "shared/src/**/*.xml",
            "desktop-app/src/**/*.kt",
            "desktop-app/src/**/*.java",
            "desktop-app/src/**/*.xml",
            "build.gradle.kts",
            "settings.gradle.kts",
            "android-app/*.gradle.kts",
            "shared/*.gradle.kts",
            "desktop-app/*.gradle.kts",
        )
    }

val applyBellSourceHeaders =
    tasks.register("applyBellSourceHeaders") {
        group = "formatting"
        description = "Adds Bell SPDX headers before compiling Bell-owned source files."
        inputs.files(bellSourceFiles)
        outputs.upToDateWhen { false }
        doLast {
            bellSourceFiles.files.forEach { sourceFile ->
                val isXml = sourceFile.extension.equals("xml", ignoreCase = true)
                val header =
                    if (isXml) "<!-- SPDX-License-Identifier: LicenseRef-Mocha-1.0 -->"
                    else "// SPDX-License-Identifier: LicenseRef-Mocha-1.0"
                val original = sourceFile.readText(Charsets.UTF_8)
                val bom = if (original.startsWith('\uFEFF')) "\uFEFF" else ""
                val body = original.removePrefix(bom)
                val hasHeader =
                    if (isXml) {
                        body.startsWith(header) ||
                            Regex(
                                    "^<\\?xml\\b[^?]*\\?>\\r?\\n" +
                                        Regex.escape(header) +
                                        "(?:\\r?\\n|$)"
                                )
                                .containsMatchIn(body)
                    } else {
                        body.startsWith(header)
                    }
                if (hasHeader) return@forEach

                val lineEnding =
                    when {
                        body.contains("\r\n") -> "\r\n"
                        body.contains('\n') -> "\n"
                        body.contains('\r') -> "\r"
                        else -> System.lineSeparator()
                    }
                val updated =
                    if (isXml) {
                        val declaration = Regex("^<\\?xml\\b[^?]*\\?>").find(body)
                        if (declaration == null) {
                            header + lineEnding + body
                        } else {
                            val newlineIndex = body.indexOf('\n', declaration.range.last + 1)
                            if (newlineIndex < 0) {
                                body.substring(0, declaration.range.last + 1) +
                                    lineEnding +
                                    header +
                                    lineEnding +
                                    body.substring(declaration.range.last + 1)
                            } else {
                                val declarationEnd =
                                    if (newlineIndex > 0 && body[newlineIndex - 1] == '\r')
                                        newlineIndex - 1
                                    else newlineIndex
                                body.substring(0, declarationEnd) +
                                    lineEnding +
                                    header +
                                    lineEnding +
                                    body.substring(newlineIndex + 1)
                            }
                        }
                    } else {
                        header + lineEnding + body
                    }
                sourceFile.writeText(bom + updated, Charsets.UTF_8)
            }
        }
    }

subprojects {
    if (name in setOf("shared", "android-app", "desktop-app")) {
        tasks.configureEach {
            if (name.startsWith("compile") || name == "preBuild") {
                dependsOn(rootProject.tasks.named("applyBellSourceHeaders"))
            }
        }
    }
}

tasks
    .matching { name.startsWith("spotless") && name.endsWith("Check") }
    .configureEach {
        dependsOn(applyBellSourceHeaders)
    }
