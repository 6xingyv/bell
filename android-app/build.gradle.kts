// SPDX-License-Identifier: LicenseRef-Mocha-1.0
import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("plugin.compose")
}

val signingProperties =
    Properties().apply {
        val propertiesFile = file("${System.getProperty("user.home")}/.gradle/gradle.properties")
        if (propertiesFile.isFile) propertiesFile.inputStream().use(::load)
    }

fun getSecretProperty(key: String): String? =
    System.getenv(key)?.takeIf { it.isNotBlank() }
        ?: (project.findProperty(key) as? String)?.takeIf { it.isNotBlank() }
        ?: signingProperties.getProperty(key)

val audioDir =
    rootProject.file(
        providers.gradleProperty("foundationAudioDir").getOrElse("vendor/foundation-audio")
    )

android {
    namespace = "com.mocharealm.bell"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.mocharealm.bell"
        minSdk = 33
        targetSdk = 37
        versionCode = providers.gradleProperty("versionCode").orElse("5").get().toInt()
        versionName = providers.gradleProperty("releaseVersion").orElse("0.1.0").get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("performance") {
            initWith(getByName("release"))
            isDebuggable = false
            matchingFallbacks += listOf("release")
        }
    }
    sourceSets["main"].apply {
        jniLibs.srcDir(audioDir.resolve("jniLibs"))
        assets.srcDir(audioDir.resolve("build/generated/audioAssets"))
    }
    androidResources { noCompress += "lpd" }
    packaging {
        jniLibs.useLegacyPackaging = false
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warning += "MissingTranslation"
    }

    signingConfigs {
        val sFile = getSecretProperty("RELEASE_STORE_FILE")
        val sPassword = getSecretProperty("RELEASE_STORE_PASSWORD")
        val kAlias = getSecretProperty("RELEASE_KEY_ALIAS")
        val kPassword = getSecretProperty("RELEASE_KEY_PASSWORD")

        if (sFile != null && sPassword != null && kAlias != null && kPassword != null) {
            val releaseSigning =
                create("release") {
                    val keystoreFile = rootProject.file(sFile)
                    if (keystoreFile.exists()) {
                        storeFile = keystoreFile
                        storePassword = sPassword
                        keyAlias = kAlias
                        keyPassword = kPassword
                    } else {
                        logger.warn("Keystore file not found at: ${keystoreFile.absolutePath}")
                    }
                    enableV1Signing = true
                    enableV2Signing = true
                    enableV3Signing = true
                    enableV4Signing = true
                }
            buildTypes.getByName("release").signingConfig = releaseSigning
            buildTypes.getByName("debug").signingConfig = releaseSigning
            buildTypes.getByName("performance").signingConfig = releaseSigning
        }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":glasmio-scaffold"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("io.ktor:ktor-client-okhttp:3.6.1")
    implementation("io.insert-koin:koin-core:4.2.2")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-ktor3:3.6.3")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.4.0")
    androidTestImplementation("io.ktor:ktor-client-okhttp:3.6.1")
    androidTestImplementation("io.ktor:ktor-client-mock:3.6.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
