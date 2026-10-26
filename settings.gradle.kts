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
    }
}

rootProject.name = "ppm-android"

// Plain Kotlin (JVM): the wire types, the network client and the state logic. Their tests run on
// this machine in seconds, with no emulator.
include(":core:protocol", ":core:client", ":core:state", ":ui:design", ":ui:chat", ":ui:web", ":app")
