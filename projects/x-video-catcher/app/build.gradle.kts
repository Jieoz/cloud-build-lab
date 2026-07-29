plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jiesa.xvideocatcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jiesa.xvideocatcher"
        // LSPosed needs Android 8.1+; Jay's target device is Android 14 (API 34).
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-probe"
    }

    buildTypes {
        // No shrinking on either variant: the Xposed entry class is loaded by name
        // from assets/xposed_init and stack traces must stay readable.
        debug { isMinifyEnabled = false }
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Provided by the Xposed/LSPosed framework at runtime — never packaged into the APK.
    compileOnly("de.robv.android.xposed:api:82")
}
