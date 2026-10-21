plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.wrapper.teams"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.wrapper.teams"
        minSdk = 29
        targetSdk = 34
        versionCode = 14
        versionName = "2.3"
    }

    // Use a committed-free local keystore when present (keeps installs
    // upgradeable without wiping data). When absent (e.g. CI), builds fall back
    // to the default debug signing so artifacts are still installable.
    val releaseKeystore = file("release.keystore")
    val hasKeystore = releaseKeystore.exists()
    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "teams123"
                keyAlias = System.getenv("KEY_ALIAS") ?: "teamswrapper"
                keyPassword = System.getenv("KEY_PASSWORD") ?: "teams123"
            }
        }
    }
    buildTypes {
        debug {
            // Same signature as release so switching between debug/release does
            // NOT wipe app data (keeps you logged in).
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = false
            signingConfig = if (hasKeystore)
                signingConfigs.getByName("release")
            else
                signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("com.google.android.material:material:1.12.0")
}
