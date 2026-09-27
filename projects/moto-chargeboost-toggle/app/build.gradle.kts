plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystorePath = System.getenv("MOTO_KEYSTORE_PATH")
val keystorePass = System.getenv("MOTO_KEYSTORE_PASSWORD")
val hasFixedKey = !keystorePath.isNullOrBlank() && file(keystorePath).exists() && !keystorePass.isNullOrBlank()

android {
    namespace = "com.jiesa.motochargeboost"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jiesa.motochargeboost"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.1.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasFixedKey) {
            create("fixed") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePass
                keyAlias = System.getenv("MOTO_KEY_ALIAS") ?: "xvc"
                keyPassword = System.getenv("MOTO_KEY_PASSWORD") ?: keystorePass
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            if (hasFixedKey) signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            if (hasFixedKey) signingConfig = signingConfigs.getByName("fixed")
            isMinifyEnabled = false
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
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}
