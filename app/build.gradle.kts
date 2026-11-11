plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lumascan.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lumascan.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 3
        versionName = "1.2.0"

        // Bundle only 64-bit ARM native binaries (cuts APK size by ~45% with zero quality loss)
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")

    // ML Kit Document Scanner (Auto edge detection, perspective crop, Whiteboard & Doc filters)
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0")

    // Lightweight Bundled Offline ML Kit v2 Recognizers (English/Latin + Devanagari)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.1")

    // Offline Multi-Threaded OpenMP Tesseract 5 Engine for Bangla + English ("ben+eng")
    implementation("cz.adaptech.tesseract4android:tesseract4android-openmp:4.9.0")

    // Coroutines for parallel multi-core page OCR
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
