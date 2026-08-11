import java.util.Properties

// Local, gitignored secrets (see local.properties). Absent on CI → key is "".
private val localSecrets =
    Properties().apply {
        rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.gitlab.arturbosch.detekt")
    id("org.jlleitschuh.gradle.ktlint")
}

val versionBase = rootProject.extra["zodiacVersionBase"] as String
val gitSha = rootProject.extra["zodiacGitSha"] as String
val gitDirty = rootProject.extra["zodiacGitDirty"] as Boolean
val commitEpoch = rootProject.extra["zodiacCommitEpoch"] as Long
val versionSuffix = if (gitDirty) "$gitSha.dirty" else gitSha

android {
    namespace = "org.pureagave.zodiac.control"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.pureagave.zodiac.control"
        // 28 = Android 9 / Fire OS 7, which is what the Fire HD 10 9th gen and
        // Fire HD 8 10th gen run. Nothing in the app needs 30 — every API gate
        // in the code is >= S (31) and Lint reports no NewApi at 28. Older 8"
        // Fires (8th gen and earlier) are Fire OS 6/5 = API 25/22 and would
        // need a further drop; check each with `getprop ro.build.version.sdk`.
        minSdk = 28
        targetSdk = 35
        versionCode = 1 // UNCHANGED — see below
        versionName = "$versionBase+$versionSuffix"

        buildConfigField("String", "VERSION_BASE", "\"$versionBase\"")
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        buildConfigField("boolean", "GIT_DIRTY", "$gitDirty")
        buildConfigField("long", "GIT_COMMIT_EPOCH_SECONDS", "${commitEpoch}L")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // Burning Man API key, injected from local.properties (never committed).
        buildConfigField("String", "BM_API_KEY", "\"${localSecrets.getProperty("BM_API_KEY", "")}\"")
    }

    // Release signing is opt-in: set ZODIAC_KEYSTORE_FILE (plus the matching
    // password/alias via env vars or gradle properties) to produce a signed
    // release for the tablet fleet. Without it, release builds unsigned — R8
    // still runs, so CI/local can verify shrinking without a keystore.
    val releaseKeystore =
        System.getenv("ZODIAC_KEYSTORE_FILE")
            ?: project.findProperty("zodiac.keystore.file") as String?

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword =
                    System.getenv("ZODIAC_KEYSTORE_PASSWORD")
                        ?: project.findProperty("zodiac.keystore.password") as String?
                keyAlias =
                    System.getenv("ZODIAC_KEY_ALIAS")
                        ?: project.findProperty("zodiac.key.alias") as String?
                keyPassword =
                    System.getenv("ZODIAC_KEY_PASSWORD")
                        ?: project.findProperty("zodiac.key.password") as String?
            }
        }
    }

    buildTypes {
        release {
            // R8 shrink + obfuscate. proguard-rules.pro keeps the usb-serial
            // driver classes the prober resolves at runtime. Validate the
            // shrunk APK on a real tablet before fleet distribution.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
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
    val composeBom = platform("androidx.compose:compose-bom:2024.11.00")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("com.github.mik3y:usb-serial-for-android:3.9.0")
    // Logging facade. The value here is the Tree abstraction: debug builds get
    // logcat, every build writes the rolling file we postmortem from.
    implementation("com.jakewharton.timber:timber:5.0.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
