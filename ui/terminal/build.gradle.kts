plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.hienlh.ppm.terminal"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    api(project(":ui:design"))
    api(project(":core:state"))
    api(project(":ui:terminal-engine"))
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
}

// The web's Nerd Symbols subsets as TTFs, with their notices, committed under `vendor/fonts`: built
// from a PPM checkout's `src/web/styles/fonts` by `scripts/build-terminal-fonts.py`; run it again
// when the web's fonts change.
androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addStaticSourceDirectory(layout.projectDirectory.dir("vendor/fonts").asFile.path)
    }
}
