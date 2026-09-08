rootProject.name = "lobotagram"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // R8/D8, used to compile the extension and the patches to DEX.
        maven("https://dl.google.com/dl/android/maven2") {
            name = "GoogleMaven"
            content { includeGroup("com.android.tools") }
        }
    }
}

include(":patches")
include(":extension")
