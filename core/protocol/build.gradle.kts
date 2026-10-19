import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

tasks.test {
    // End-to-end tests run only when a scratch server is named (see android/CLAUDE.md).
    environment("PPM_E2E_URL", System.getenv("PPM_E2E_URL") ?: "")
    environment("PPM_E2E_PASSWORD", System.getenv("PPM_E2E_PASSWORD") ?: "")
    environment("PPM_E2E_PROJECT", System.getenv("PPM_E2E_PROJECT") ?: "")
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
