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
        versionCode = 6
        versionName = "0.6.0-probe"
    }

    buildFeatures { buildConfig = true }

    // A fixed signing key, so every build can be installed over the previous one.
    // Android refuses an update whose signature differs, and the auto-generated debug
    // keystore is per-machine — on a CI runner it is regenerated every run, which made
    // each build uninstallable over the last.
    //
    // Supplied via env (CI secrets). Absent locally, the build still works for
    // compiling and testing but produces a debug-key APK that cannot be used for
    // in-place upgrades; CI fails outright rather than publishing such an APK, so the
    // fallback is never what reaches a device.
    val keystorePath = System.getenv("XVC_KEYSTORE_PATH")
    val keystorePass = System.getenv("XVC_KEYSTORE_PASSWORD")
    val keyAliasName = System.getenv("XVC_KEY_ALIAS") ?: "xvc"
    val keyPassword = System.getenv("XVC_KEY_PASSWORD") ?: keystorePass
    val hasFixedKey = !keystorePath.isNullOrBlank() && file(keystorePath).exists() &&
        !keystorePass.isNullOrBlank()

    signingConfigs {
        if (hasFixedKey) {
            create("fixed") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePass
                keyAlias = keyAliasName
                this.keyPassword = keyPassword
                // v1 matters: LSPosed parses the APK on older paths, and some file
                // managers install via the legacy verifier.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        // No shrinking on either variant: the Xposed entry class is loaded by name
        // from assets/xposed_init and stack traces must stay readable.
        debug {
            isMinifyEnabled = false
            if (hasFixedKey) signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            isMinifyEnabled = false
            if (hasFixedKey) signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    testOptions {
        unitTests {
            // Robolectric drives the real ProbeSink against a genuine Android context
            // and asserts on bytes that actually reached disk, which is the only way
            // to catch a dead log sink without a device.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // Provided by the Xposed/LSPosed framework at runtime — never packaged into the APK.
    compileOnly("de.robv.android.xposed:api:82")

    // No androidx runtime dependency: the probe UI is plain framework views, and the
    // log is written by the host process straight into shared Downloads.

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.test:core:1.5.0")
}
