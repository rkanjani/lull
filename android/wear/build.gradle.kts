plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/** The phone app's version, kept in the Xcode project's MARKETING_VERSION. */
val marketingVersion: String by rootProject.extra

/** Release signing comes from the environment (CI), as the phone app's does. The Data Layer only connects the two when both are signed alike. */
val uploadKeystore: String? = System.getenv("LULL_UPLOAD_KEYSTORE")

android {
    // The watch app shares the phone app's package name, which is how the Wearable Data Layer pairs them.
    namespace = "com.rohankanjani.lull.wear"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.rohankanjani.lull"
        // Wear OS 3.
        minSdk = 30
        targetSdk = 36
        // Google Play needs every bundle of an app to have its own version code; the watch's is the phone's plus one.
        versionCode = ((findProperty("versionCode") as String?)?.toInt() ?: 1) + 1
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
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
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
    implementation(project(":shared"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.tiles)
    implementation(libs.wear.protolayout)
    implementation(libs.wear.protolayout.expression)
    implementation(libs.wear.complications.data.source)
    implementation(libs.concurrent.futures)
    // How the watch reaches Lull on the phone, which reaches the BedJet.
    implementation(libs.play.services.wearable)

    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
