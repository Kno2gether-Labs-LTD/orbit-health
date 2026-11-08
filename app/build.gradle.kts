plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Room schema history is committed (app/schemas) so future migrations can be written and tested.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "dev.avijit.orbithealth"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.avijit.orbithealth"
        minSdk = 31
        targetSdk = 35
        versionCode = 6
        versionName = "0.7.0"
    }
    // Release signing is optional: only configured when all four Gradle properties are present
    // (e.g. in ~/.gradle/gradle.properties, never in the repository). ORBITHEALTH_RELEASE_* wins; otherwise the
    // shared Orbit apps key (ORBIT_RELEASE_*) is used. Without either, the release build stays unsigned.
    val signingKeys = listOf("STORE_FILE", "STORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")
    val releaseProps = listOf("ORBITHEALTH_RELEASE_", "ORBIT_RELEASE_")
        .map { prefix -> signingKeys.associateWith { findProperty(prefix + it) as String? } }
        .firstOrNull { props -> props.values.all { it != null } }
    signingConfigs {
        if (releaseProps != null) create("release") {
            storeFile = file(releaseProps.getValue("STORE_FILE")!!)
            storePassword = releaseProps.getValue("STORE_PASSWORD")
            keyAlias = releaseProps.getValue("KEY_ALIAS")
            keyPassword = releaseProps.getValue("KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // BuildConfig: VERSION_NAME for the update check, DEBUG to keep debug-only behaviour out of release.
    buildFeatures { compose = true; buildConfig = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation(project(":ring-core"))
    implementation(project(":ring-ycbt"))
    // Wire DTOs shared with Orbit Server (pairing link, ingest, state, ask, access log).
    implementation(project(":sync-protocol"))
    // HTTPS to the user's own paired Orbit Server only, pinned to its certificate (server/PinnedTls).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // QR scanning for pairing (Agent & sync). The camera is only asked for when the user taps Scan.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    // Real backdrop blur for glass cards (RenderEffect, minSdk 31). 1.5.4 is the newest Haze still on Compose 1.7.
    implementation("dev.chrisbanes.haze:haze:1.5.4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.9.3")
    // App lock: BiometricPrompt (needs a FragmentActivity; fragment pinned past 1.3 for the Activity Result APIs)
    // and ProcessLifecycleOwner to time how long the app has been in the background.
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.9.1")
    implementation("androidx.lifecycle:lifecycle-process:2.8.5")
    // 2.8.x is the line built on Compose 1.7 (BOM 2024.10.01); also brings lifecycle-viewmodel-compose.
    implementation("androidx.navigation:navigation-compose:2.10.2")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("net.zetetic:sqlcipher-android:4.6.1@aar")
    implementation("androidx.sqlite:sqlite:2.4.0")
    implementation("androidx.datastore:datastore-preferences:1.3.0")
    testImplementation("org.robolectric:robolectric:4.13")
    // Compose UI tests on Robolectric (the lock overlay's focus/touch blocking).
    testImplementation(platform("androidx.compose:compose-bom:2024.10.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation(testFixtures(project(":ring-core"))) // FakeFamily, FakeRingDriver
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // A local HTTPS server with self-signed certificates for the pinned-TLS client tests.
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
}

// Compose UI tests need ui-test-manifest (debug-only); the debug variant covers the same sources.
tasks.configureEach { if (name == "testReleaseUnitTest") enabled = false }
