buildscript {
    // AGP 9 ships built-in Kotlin 2.2; the Compose BOM needs a newer compiler.
    dependencies { classpath(libs.kotlin.gradle.plugin) }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
