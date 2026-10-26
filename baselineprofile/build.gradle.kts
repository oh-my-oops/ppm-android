plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

// `BaselineProfileGenerator` opens the app, signs in, opens a chat and scrolls it, and the plugin
// keeps every method that ran in app/src/release/generated/baselineProfiles: Android compiles
// those ahead of time instead of interpreting them.
// `StartupBenchmarks` and `ScrollBenchmarks` measure a cold start and a scrolled chat with and
// without it. All of them drive a release build of the app on a device and need a PPM server to
// sign in to, named on the device (`Target` in PpmJourneys.kt).
android {
    namespace = "com.hienlh.ppm.baselineprofile"
    compileSdk = 37
    defaultConfig {
        // Baseline Profiles are collected from Android 9; without root, from 13.
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The only devices here are emulators. Their times compare one build with another on the
        // same emulator; they are not a phone's.
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }
    targetProjectPath = ":app"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
