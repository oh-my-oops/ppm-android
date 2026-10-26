plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The release key is never in the repository: its keystore and passwords are Gradle properties
// (`~/.gradle/gradle.properties`, or `ORG_GRADLE_PROJECT_ppmRelease…` in CI). Without them a
// release build is signed with the debug key, so R8's output installs over a debug build for
// testing and cannot pass for a published one.
val releaseKey = listOf("StoreFile", "StorePassword", "KeyAlias", "KeyPassword")
    .associateWith { providers.gradleProperty("ppmRelease$it").orNull }
    .takeIf { key -> key.values.all { it != null } }

// A release's version is its tag's (`android-vX.Y.Z`) and its code is made from that version, so
// every release has a higher code than the ones it updates. `-PppmVersion` builds another version,
// and `-PppmReleasesUrl` points a build at a releases list of its own, to test updating.
val appVersion = providers.gradleProperty("ppmVersion").getOrElse("0.1.0")
val releasesUrl = providers.gradleProperty("ppmReleasesUrl").orNull

android {
    namespace = "com.hienlh.ppm.app"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.hienlh.ppm"
        minSdk = 26
        // 37 puts every LAN address behind a runtime permission (Android 17 local network
        // protection), and PPM is usually on the LAN: connections simply time out without it.
        targetSdk = 36
        versionName = appVersion
        versionCode = appVersion.split(".").map(String::toInt).let { (major, minor, patch) -> major * 1_000_000 + minor * 1_000 + patch }
        buildConfigField("String", "RELEASES_URL", releasesUrl?.let { "\"$it\"" } ?: "null")
    }
    signingConfigs {
        if (releaseKey != null) create("release") {
            storeFile = file(releaseKey.getValue("StoreFile")!!)
            storePassword = releaseKey.getValue("StorePassword")
            keyAlias = releaseKey.getValue("KeyAlias")
            keyPassword = releaseKey.getValue("KeyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":ui:chat"))
    implementation(project(":ui:web"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidsvg)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
