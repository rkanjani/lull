// What the phone app and the watch app both use: schedules, history, what the widgets and the watch show, and the
// night curve. The twin of the iPhone app's Shared folder.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.rohankanjani.lull.shared"
    compileSdk = 37

    defaultConfig {
        // Wear OS 3, the oldest the watch app runs on.
        minSdk = 30
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":bedjetkit"))
    api(libs.kotlinx.serialization.json)
    api(platform(libs.compose.bom))
    api(libs.compose.runtime)
    api(libs.compose.ui)
    api(libs.compose.foundation)
    api(libs.compose.icons.extended)
}
