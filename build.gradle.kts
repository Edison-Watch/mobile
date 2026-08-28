// Top-level build file. Plugins are declared here (apply false) and applied in
// the module `build.gradle.kts` files. Versions come from `gradle/libs.versions.toml`.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}
