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
 * The screenshot library a page check hands to the page (`modern-screenshot/dist/index.js`, which
 * the web imports `?raw`): copied from the repository's node_modules, so it is the web's own
 * version and nothing generated is kept in git.
 */
abstract class CopyScreenshotLib : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val source: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val dir = outputDir.get().dir("preview").asFile
        dir.mkdirs()
        source.get().asFile.copyTo(dir.resolve("modern-screenshot.js"), overwrite = true)
    }
}

val copyScreenshotLib = tasks.register<CopyScreenshotLib>("copyScreenshotLib") {
    source.set(rootProject.layout.projectDirectory.file("../node_modules/modern-screenshot/dist/index.js"))
    outputDir.set(layout.buildDirectory.dir("generated/screenshot"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyScreenshotLib, CopyScreenshotLib::outputDir)
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
