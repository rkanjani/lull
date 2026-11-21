/** Both apps, and the watch app, ship under one version number, kept in the Xcode project's MARKETING_VERSION. */
val marketingVersion: String by extra(
    rootDir.resolve("../Lull.xcodeproj/project.pbxproj").readText()
        .let { Regex("MARKETING_VERSION = ([0-9.]+);").find(it)?.groupValues?.get(1) ?: error("MARKETING_VERSION not found") },
)

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
