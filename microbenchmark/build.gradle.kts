plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.androidx.benchmark)
}

// `DecodeBenchmark` times a 10 MB `turn_events` frame decoded two ways on a device: the app's
// `JsonElement` tree read field by field, and straight into the generated protocol types.
//   ./gradlew :microbenchmark:connectedReleaseAndroidTest   (one device attached; results in
//   microbenchmark/build/outputs/connected_android_test_additional_output/)
android {
    namespace = "com.hienlh.ppm.microbenchmark"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.benchmark.junit4.AndroidBenchmarkRunner"
        // An emulator's times compare the two decoders with each other; they are not a phone's.
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR,LOW-BATTERY,UNLOCKED"
    }
    // A debuggable build runs without most of ART's optimizations, which is not what users get.
    testBuildType = "release"
    buildTypes {
        release { isDefault = true }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    androidTestImplementation(project(":core:protocol"))
    androidTestImplementation(libs.androidx.benchmark.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
