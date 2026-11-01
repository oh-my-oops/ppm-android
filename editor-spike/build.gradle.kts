plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val localCandidate = providers.gradleProperty("aardinkSpikeAar").orNull

android {
    namespace = "com.hienlh.ppm.editorspike"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.hienlh.ppm.editorspike"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        buildConfigField("boolean", "LOCAL_CANDIDATE", (localCandidate != null).toString())
    }
    buildTypes {
        release { signingConfig = signingConfigs.getByName("debug") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.kotlinx.coroutines.android)
    // Spike only: do not ship or adopt until the device/IME/performance gates pass.
    if (localCandidate == null) implementation("com.aardarch:aardink:0.6.2")
    else {
        implementation(files(localCandidate))
        // Dependencies from the 0.6.2 Android POM; file AARs have no transitive metadata.
        implementation("org.jetbrains.compose.runtime:runtime:1.12.1")
        implementation("org.jetbrains.compose.foundation:foundation:1.12.1")
        implementation("org.jetbrains.compose.ui:ui:1.12.1")
        implementation("org.jetbrains.compose.material3:material3:1.9.0")
        implementation(libs.kotlinx.serialization.json)
    }
}
