// Root build file. Per ADR-012, Phase 1 bootstraps as a single :app module;
// this file only declares plugin versions shared across the (currently single) module.
//
// AGP 9.0+ has built-in Kotlin support; the separate org.jetbrains.kotlin.android
// plugin is not applied (verified live against AGP 9.4.0 during FND-001, 2026-09-15).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
