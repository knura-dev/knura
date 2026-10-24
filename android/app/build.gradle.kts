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
        versionCode = 6
        versionName = "0.3.1"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
