plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/** Both apps ship under one version number, kept in the Xcode project's MARKETING_VERSION. */
val marketingVersion: String by rootProject.extra

/** Release signing comes from the environment (CI) so no key or password is ever committed. */
val uploadKeystore: String? = System.getenv("LULL_UPLOAD_KEYSTORE")

android {
    namespace = "com.rohankanjani.lull"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.rohankanjani.lull"
        minSdk = 31
        targetSdk = 36
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = marketingVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (uploadKeystore != null) {
            create("upload") {
                storeFile = file(uploadKeystore)
                storePassword = System.getenv("LULL_UPLOAD_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("LULL_UPLOAD_KEY_ALIAS")
                keyPassword = System.getenv("LULL_UPLOAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without the upload key, the debug key signs it, so anyone can install a release build to check it.
            // Google Play only accepts the upload key.
            signingConfig = signingConfigs.getByName(if (uploadKeystore != null) "upload" else "debug")
        }
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
    implementation(project(":bedjetkit"))
    implementation(project(":shared"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    implementation(libs.glance.appwidget)
    // Glance brings WorkManager 2.7, whose database R8 strips from release builds, so every launch crashed.
    // Newer WorkManager carries the keep rules it needs.
    implementation(libs.work.runtime)
    // How the watch app reaches Lull on the phone, which reaches the BedJet.
    implementation(libs.play.services.wearable)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.compose.ui.test.junit4.accessibility)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.glance.appwidget.testing)
}
