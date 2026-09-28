plugins {
    alias(libs.plugins.agp.app)
}

val verName: String by rootProject.extra
val verCode: Int by rootProject.extra
val releaseStoreFile = providers.environmentVariable("RELEASE_STORE_FILE")
val releaseStorePassword = providers.environmentVariable("RELEASE_STORE_PASSWORD")
val releaseKeyAlias = providers.environmentVariable("RELEASE_KEY_ALIAS")
val releaseKeyPassword = providers.environmentVariable("RELEASE_KEY_PASSWORD")

android {
    namespace = "io.github.timeline_unlocker.xposed"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.timeline_unlocker.xposed"
        minSdk = 26
        targetSdk = 36
        versionCode = verCode
        versionName = verName
    }

    signingConfigs {
        create("release") {
            storeFile = releaseStoreFile.orElse("/missing-release.jks").map { file(it) }.get()
            storePassword = releaseStorePassword.orElse("missing").get()
            keyAlias = releaseKeyAlias.orElse("missing").get()
            keyPassword = releaseKeyPassword.orElse("missing").get()
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
    // Writable side for the module's own UI process: XposedService + its XposedProvider.
    // Bundled (implementation) because LSPosed only provides the hook `api` at runtime.
    implementation(files("libs/libxposed-service-102.0.0.aar"))
    implementation(files("libs/libxposed-interface-102.0.0.aar"))
    testImplementation(libs.junit)
}
