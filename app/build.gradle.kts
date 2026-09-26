import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}

// Release signing credentials live in `key.properties` at the repo root.
// That file is gitignored — never commit it. A template is committed as
// `key.properties.example`.
//
// WARNING: if this file is missing, the release build falls back to the debug
// key so that `./gradlew assembleDebug` and CI still work. A debug-signed
// release is accepted by Google Play ONCE and then permanently blocks every
// future update. See RELEASE.md before producing any uploadable artifact.
val keystorePropertiesFile = rootProject.file("key.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseKey = keystorePropertiesFile.exists()

if (!hasReleaseKey) {
    logger.warn(
        "HAT: key.properties not found — release builds will be signed with the " +
        "DEBUG key. Do NOT upload such a build to Google Play. See RELEASE.md."
    )
}

android {
    namespace = "andromedvn.heuristic.activity.tracker"
    compileSdk = 36

    defaultConfig {
        applicationId = "andromedvn.heuristic.activity.tracker"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.1"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                // Resolve against the repo root, not the `app/` module directory.
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "release" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // The Compose compiler now comes from the org.jetbrains.kotlin.plugin.compose
    // plugin, which must track the Kotlin version. The old
    // `composeOptions { kotlinCompilerExtensionVersion = ... }` block is gone.
    buildFeatures { compose = true }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    // --- JVM unit tests (Phase B) -----------------------------------------
    // Pure-logic tests only. No Robolectric: this machine has 4.7 GB RAM and
    // Robolectric's instrumented class loading triggers the same AAPT2/JVM
    // memory pressure that broke bundleRelease.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
