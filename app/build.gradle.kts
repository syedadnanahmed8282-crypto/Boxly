plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.boxly.app"
    compileSdk = 34

    defaultConfig {
        // Placeholder id: ApkBuilder rewrites it (same length) for every generated app.
        applicationId = "com.boxly.pkg00000000"
        minSdk = 26
        // 29 on purpose: resources.arsc may be recompressed when we repack the APK.
        targetSdk = 29
        versionCode = 5
        versionName = "5.0.0"
    }
    signingConfigs {
        // Same key on every GitHub build (from repo secrets), so new APKs can update old installs.
        create("boxly") {
            val ks = System.getenv("BOXLY_KEYSTORE")
            if (ks != null && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = System.getenv("BOXLY_STORE_PASSWORD")
                keyAlias = System.getenv("BOXLY_KEY_ALIAS")
                keyPassword = System.getenv("BOXLY_STORE_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (System.getenv("BOXLY_KEYSTORE") != null) signingConfigs.getByName("boxly") else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = false }
    lint { checkReleaseBuilds = false; abortOnError = false }
    packaging { resources { excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/DEPENDENCIES") } }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("com.android.tools.build:apksig:8.5.2")
}
