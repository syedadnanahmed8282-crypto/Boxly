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
        versionCode = 3
        versionName = "3.0.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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
