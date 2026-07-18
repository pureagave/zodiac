plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("io.gitlab.arturbosch.detekt")
    id("org.jlleitschuh.gradle.ktlint")
}

android {
    namespace = "ai.openclaw.zodiacbeacon"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.openclaw.zodiacbeacon"
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
    // A small utility app — don't let cosmetic lint warnings (e.g. hardcoded
    // strings in the debug UI) fail the shared gate.
    lint {
        abortOnError = false
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
}
