// AGP 9 compiles Kotlin itself (built-in Kotlin): Android modules apply no Kotlin plugin of their
// own. The Compose compiler plugin pins kotlin-gradle-plugin to its version on this classpath, and
// the JVM modules use that same version.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
    alias(libs.plugins.androidx.benchmark) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
