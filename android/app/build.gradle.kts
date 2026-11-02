plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.knura.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.knura.android"
        // Android 11: BiometricPrompt with a screen-lock fallback, and Keystore keys that need it.
        minSdk = 30
        targetSdk = 35
        versionCode = 16
        versionName = "0.8.3"
    }

    buildFeatures {
        buildConfig = true
    }

    // Release builds are signed with Knura's own key when the release workflow provides it
    // (from the ANDROID_KEYSTORE secrets), so every release can update the one before it. Without
    // it, assembleRelease gives an unsigned APK and CI uses the debug build instead.
    val keystore = System.getenv("KNURA_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (keystore != null) {
            create("knura") {
                storeFile = keystore
                storePassword = System.getenv("KNURA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KNURA_KEY_ALIAS")
                keyPassword = System.getenv("KNURA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystore != null) {
                signingConfig = signingConfigs.getByName("knura")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Google's QR scanner: scans the pairing code without the app needing camera permission.
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    testImplementation("junit:junit:4.13.2")
    // The real org.json for unit tests; Android's own is only stubs off the phone.
    testImplementation("org.json:json:20240303")
}
