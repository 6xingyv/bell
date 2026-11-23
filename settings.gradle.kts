// SPDX-License-Identifier: LicenseRef-Mocha-1.0
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        if (providers.gradleProperty("localMavenRepo").isPresent) {
            maven {
                url = uri(providers.gradleProperty("localMavenRepo").get())
                content { includeGroupByRegex("com\\.mocharealm.*") }
            }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "Bell"

include(":shared", ":android-app", ":desktop-app")

listOf("core", "glass", "icons", "ui", "scaffold").forEach {
    include(":glasmio-$it")
    project(":glasmio-$it").projectDir = file("vendor/Glasmio/glasmio-$it")
}
