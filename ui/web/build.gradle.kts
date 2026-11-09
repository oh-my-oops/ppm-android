plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.hienlh.ppm.web"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

/**
 * The screenshot library a page check hands to the page: `vendor/screenshot/preview/modern-screenshot.js`
 * is the web's own `modern-screenshot/dist/index.js` (which it imports `?raw`), copied from a PPM
 * checkout's node_modules. Copy it again when the web moves to another version.
 */
androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addStaticSourceDirectory(layout.projectDirectory.dir("vendor/screenshot").asFile.path)
    }
}

dependencies {
    api(project(":ui:design"))
    api(project(":core:state"))
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
