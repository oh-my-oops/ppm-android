plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

val upstreamSources = configurations.create("upstreamSources") { isTransitive = false }
val upstreamAar = configurations.create("upstreamAar") { isTransitive = false }
val upstreamPom = configurations.create("upstreamPom") { isTransitive = false }

dependencies {
    upstreamSources("org.connectbot:termlib:0.3.13:sources@jar")
    upstreamAar("org.connectbot:termlib:0.3.13@aar")
    upstreamPom("org.connectbot:termlib:0.3.13@pom")

    // The pinned POM, including its explicit versions and Compose BOM. These are ordinary
    // constraints, as upstream's are: the app may resolve a newer compatible Compose patch.
    api(platform("androidx.compose:compose-bom:2026.09.00"))
    api("org.jetbrains.kotlin:kotlin-stdlib:2.4.20")
    implementation("org.jetbrains.kotlin:kotlin-parcelize-runtime:2.4.20")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    api("androidx.compose.ui:ui:1.11.2")
    api("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    api("androidx.compose.runtime:runtime:1.11.4")
    implementation("androidx.compose.foundation:foundation:1.11.2")
    testImplementation(libs.junit)
    // The IME tests run the real InputConnection, Editable and KeyEvent on the JVM (Robolectric),
    // and the real KeyboardHandler over a mocked emulator, whose JNI cannot load on this machine.
    testImplementation(libs.robolectric)
    testImplementation(libs.mockito.core)
}

abstract class PatchTerminalEngine : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val sourcesJar: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val aar: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val pom: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val script: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val imeHelper: RegularFileProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val licenses: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:Internal abstract val kotlinDir: DirectoryProperty
    @get:Internal abstract val resDir: DirectoryProperty
    @get:Internal abstract val jniDir: DirectoryProperty
    @get:Internal abstract val assetsDir: DirectoryProperty
    @get:Internal abstract val resourcesDir: DirectoryProperty
    @get:Internal abstract val manifest: RegularFileProperty
    @get:Inject abstract val exec: ExecOperations

    @TaskAction fun patch() {
        exec.exec {
            commandLine("python3", "-B", "-I", script.get().asFile.path,
                "--sources", sourcesJar.get().asFile.path, "--aar", aar.get().asFile.path,
                "--pom", pom.get().asFile.path, "--output", outputDir.get().asFile.path)
        }
    }
}

val patchTerminalEngine = tasks.register<PatchTerminalEngine>("patchTerminalEngine") {
    // Optional local artifacts are still checked against the exact same SHA-256 pins.
    val localSources = providers.gradleProperty("termlibSources").orNull
    if (localSources != null) sourcesJar.set(file(localSources))
    else sourcesJar.set(layout.file(upstreamSources.elements.map { it.single().asFile }))
    aar.set(layout.file(upstreamAar.elements.map { it.single().asFile }))
    pom.set(layout.file(upstreamPom.elements.map { it.single().asFile }))
    script.set(layout.projectDirectory.file("../../scripts/patch-terminal-engine.py"))
    imeHelper.set(layout.projectDirectory.file("../../scripts/terminal_ime_patch.py"))
    licenses.from(layout.projectDirectory.file("../../licenses/Apache-2.0.txt"), layout.projectDirectory.dir("licenses"))
    outputDir.set(layout.buildDirectory.dir("generated/termlib"))
    kotlinDir.set(outputDir.dir("kotlin"))
    resDir.set(outputDir.dir("res"))
    jniDir.set(outputDir.dir("jni"))
    assetsDir.set(outputDir.dir("assets"))
    resourcesDir.set(outputDir.dir("javaResources"))
    manifest.set(outputDir.file("AndroidManifest.xml"))
}
val generated = patchTerminalEngine.flatMap { it.outputDir }

android {
    namespace = "org.connectbot.terminal"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = providers.gradleProperty("testInstrumentationRunner").getOrElse("org.connectbot.terminal.EngineInstrumentation")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    testOptions.unitTests.isIncludeAndroidResources = true
    // Preserve the pinned JNI bytes even on hosts with an NDK available for stripping.
    packaging.jniLibs.keepDebugSymbols += "**/libjni_cb_term.so"
}

androidComponents {
    onVariants { variant ->
        variant.sources.kotlin?.addGeneratedSourceDirectory(patchTerminalEngine, PatchTerminalEngine::kotlinDir)
        variant.sources.res?.addGeneratedSourceDirectory(patchTerminalEngine, PatchTerminalEngine::resDir)
        variant.sources.jniLibs?.addGeneratedSourceDirectory(patchTerminalEngine, PatchTerminalEngine::jniDir)
        variant.sources.assets?.addGeneratedSourceDirectory(patchTerminalEngine, PatchTerminalEngine::assetsDir)
        variant.sources.resources?.addGeneratedSourceDirectory(patchTerminalEngine, PatchTerminalEngine::resourcesDir)
        variant.sources.manifests.addGeneratedManifestFile(patchTerminalEngine, PatchTerminalEngine::manifest)
        variant.consumerProguardFiles.add(generated.map { it.file("proguard.txt") })
        // What the JVM tests and the instrumentation both run (ResizeDiff): one copy, compiled by each, so the two cannot differ.
        (variant as? com.android.build.api.variant.HasUnitTest)?.unitTest?.sources?.kotlin?.addStaticSourceDirectory("src/sharedTest/kotlin")
        (variant as? com.android.build.api.variant.HasAndroidTest)?.androidTest?.sources?.kotlin?.addStaticSourceDirectory("src/sharedTest/kotlin")
    }
}
