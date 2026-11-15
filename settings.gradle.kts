val coreOnly = System.getenv("DF_CORE_ONLY") != null
pluginManagement {
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.2.20"
        id("org.jetbrains.kotlin.android") version "2.2.20"
        id("com.android.application") version "8.11.1"
    }
    repositories {
        if (System.getenv("DF_CORE_ONLY") == null) google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        if (!coreOnly) google()
        mavenCentral()
    }
}
rootProject.name = "DocumentFacilitator"
include(":core")
// The Android module needs the Android SDK + Google Maven. Set DF_CORE_ONLY=1 to work on
// the pure-Kotlin document engine (and run its tests) on a machine without them.
if (!coreOnly) include(":app")
