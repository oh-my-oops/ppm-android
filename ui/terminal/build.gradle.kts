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
}

dependencies {
    api(project(":ui:design"))
    api(project(":core:state"))
    api(project(":ui:terminal-engine"))
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}

abstract class BuildTerminalFonts : DefaultTask() {
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
        exec.exec { commandLine("python3", "-I", script.get().asFile.path, outputDir.get().asFile.path) }
    }
}
val terminalFonts = tasks.register<BuildTerminalFonts>("buildTerminalFonts") {
    val script = rootProject.layout.projectDirectory.file("scripts/build-terminal-fonts.py")
    val sources = rootProject.layout.projectDirectory.dir("../src/web/styles/fonts")
    this.script.set(script)
    this.sources.from(script, fileTree(sources) { include("nerd-symbols-*.woff2", "*LICENSE*", "*NOTICE*") })
    this.sources.from(rootProject.file("../THIRD-PARTY-NOTICES.md"), rootProject.file("../licenses"), rootProject.file("ui/design/licenses/Monaspace-OFL.txt"))
    outputDir.set(layout.buildDirectory.dir("generated/terminalFonts"))
}
androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(terminalFonts, BuildTerminalFonts::outputDir)
    }
}
