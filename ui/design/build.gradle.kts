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
 * The code highlighting's assets — Shiki's bundle and every grammar — built from the repository's
 * own Shiki by `android/scripts/build-highlighter.ts`, so they never drift from the web's and
 * nothing generated is kept in git. Needs bun and the repository's node_modules.
 */
abstract class BuildHighlighter : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Internal
    abstract val script: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val exec: ExecOperations

    @TaskAction
    fun build() {
        exec.exec { commandLine("bun", script.get().asFile.path, outputDir.get().dir("shiki").asFile.path) }
    }
}

val buildHighlighter = tasks.register<BuildHighlighter>("buildHighlighter") {
    val scripts = rootProject.layout.projectDirectory.dir("scripts")
    script.set(scripts.file("build-highlighter.ts"))
    sources.from(scripts.file("build-highlighter.ts"), scripts.dir("highlighter"), rootProject.layout.projectDirectory.file("../node_modules/shiki/package.json"))
    outputDir.set(layout.buildDirectory.dir("generated/highlighter"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(buildHighlighter, BuildHighlighter::outputDir)
    }
}

dependencies {
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.javascriptengine)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.kotlinx.serialization.json)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
