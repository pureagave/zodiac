plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("io.gitlab.arturbosch.detekt")
    id("org.jlleitschuh.gradle.ktlint")
}

android {
    namespace = "org.pureagave.zodiac.beacon"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.pureagave.zodiac.beacon"
        // The beacon runs on the rugged sensor phone (XCover Pro = Android 10),
        // so its floor is lower than the tablet app's (minSdk 30).
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    // Robolectric needs the real android.jar resources (manifest, etc.) rather
    // than the "throws on any call" unit-test stub — required from B5 on, since
    // TelemetryBroadcaster.start()/TelemetryService need a working Context
    // (SharedPreferences, SensorManager, WifiManager) to be exercised at all in a
    // JVM test, and B4/B3 share the same harness for ShadowPowerManager /
    // service-start assertions.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
    // The beacon is the module where a manifest / permission / foreground-service-type
    // / API-level mistake is fleet-fatal: it must survive a reboot and run unattended
    // for a week feeding 8-10 tablets, and Lint is the only automated check that
    // reads the manifest at all. It used to be `false` to keep cosmetic warnings
    // (hardcoded strings in the provisioning UI) out of the shared gate; those are
    // now suppressed at their source with a rationale, so the gate is real.
    // Warnings still only report — only errors abort, same as :app.
    lint {
        abortOnError = true
    }
}

detekt {
    config.setFrom("$rootDir/config/detekt/detekt.yml")
    buildUponDefaultConfig = true
    allRules = false
}

ktlint {
    android.set(true)
    ignoreFailures.set(false)
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // Pulled forward from the original B4 plan into B5: safeForegroundTypes stays
    // a pure/no-Android-import function, but TelemetryBroadcaster.start() itself
    // touches real Context system services (SharedPreferences, SensorManager,
    // WifiManager) and needs a working Context to be testable at all — the
    // android.jar unit-test stub throws on every call. Robolectric + a fake
    // BeaconGpsHandle (never a real LocationManager) is the harness. Shared by
    // B4 (ShadowPowerManager) and B3 (Robolectric BroadcastReceiver dispatch).
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.6.1")
}
