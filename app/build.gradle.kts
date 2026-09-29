import org.gradle.api.GradleException
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
// A release signed with the debug key cannot be replaced by a correctly
// signed one: installing it over a real release fails with
// INSTALL_FAILED_UPDATE_INCOMPATIBLE, and the artifact is un-updatable
// forever. Nothing can recover the key — no remote keeps a copy — so a
// missing key.properties must FAIL the release build rather than quietly
// produce something that looks fine.
//
// CI has no key.properties. It passes -PallowDebugSignedRelease=true, which
// exists only for throwaway builds that are never published. It is opt-in
// per invocation precisely so it cannot happen by accident.
val keystorePropertiesFile = rootProject.file("key.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseKey = keystorePropertiesFile.exists()
val isReleaseTask = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
val allowDebugSignedRelease = (project.findProperty("allowDebugSignedRelease") as String?)?.toBoolean() ?: false

if (!hasReleaseKey) {
    if (isReleaseTask && !allowDebugSignedRelease) {
        throw GradleException(
            "HAT: key.properties not found, refusing to produce a debug-signed release.\n" +
                "  A debug-signed release can never be replaced by a signed one, and the\n" +
                "  signing key is not recoverable. See RELEASE.md.\n" +
                "  If you are building locally to check that release compiles, re-run with\n" +
                "  -PallowDebugSignedRelease=true. Never publish a build made that way."
        )
    }
    logger.warn(
        "HAT: key.properties not found — any release build will use the DEBUG key. " +
            "Do NOT publish such a build. See RELEASE.md."
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
