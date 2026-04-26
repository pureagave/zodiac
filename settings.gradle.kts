pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // usb-serial-for-android (Phase 4e — USB GPS dongles)
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "ZodiacControl"
include(":app")
