plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.hienlh.ppm.design"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

/**
 * The code highlighting's assets (Shiki's bundle and every grammar) and the file icons' (the
 * vscode-icons glyphs and the tables that name them) are committed under `vendor/`. They were built
 * from the web app's own Shiki and icon tables by `scripts/build-highlighter.ts` and
 * `scripts/build-file-icons.ts`, run inside a PPM checkout; run those again when the web's change (bun, the PPM repository's
 * node_modules, output into these folders).
 */
val highlighterAssets = layout.projectDirectory.dir("vendor/highlighter")
val fileIconAssets = layout.projectDirectory.dir("vendor/file-icons")

// The icon tests resolve names against the tables the app ships, not a copy of them.
tasks.withType<Test>().configureEach {
    val icons = fileIconAssets.asFile.path
    inputs.dir(icons).withPropertyName("fileIcons").withPathSensitivity(PathSensitivity.RELATIVE)
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dppm.fileIcons=$icons") })
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addStaticSourceDirectory(highlighterAssets.asFile.path)
        variant.sources.assets?.addStaticSourceDirectory(fileIconAssets.asFile.path)
    }
}

dependencies {
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.javascriptengine)
    implementation(libs.androidsvg)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.kotlinx.serialization.json)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
