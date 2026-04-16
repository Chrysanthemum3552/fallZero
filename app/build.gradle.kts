plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.de.undercouch.download)
}

android {
    namespace = "com.fallzero.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fallzero.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-mvp"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)

    // Navigation
    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)

    // Lifecycle + ViewModel
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.livedata)
    implementation(libs.lifecycle.runtime)

    // CameraX
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    // MediaPipe Tasks Vision
    implementation(libs.mediapipe.vision)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // WorkManager
    implementation(libs.workmanager.ktx)

    // Lottie Animation (운동 가이드 애니메이션)
    implementation(libs.lottie)

    // RecyclerView (운동 목록)
    implementation(libs.recyclerview)
}

// ── MediaPipe 모델 자동 다운로드 ──
val assetDir = "$projectDir/src/main/assets"

val downloadPoseLandmarkerHeavy by tasks.registering(de.undercouch.gradle.tasks.download.Download::class) {
    src("https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_heavy/float16/latest/pose_landmarker_heavy.task")
    dest("$assetDir/pose_landmarker_heavy.task")
    overwrite(false)
}

val downloadPoseLandmarkerFull by tasks.registering(de.undercouch.gradle.tasks.download.Download::class) {
    src("https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_full/float16/latest/pose_landmarker_full.task")
    dest("$assetDir/pose_landmarker_full.task")
    overwrite(false)
}

tasks.named("preBuild") {
    dependsOn(downloadPoseLandmarkerHeavy, downloadPoseLandmarkerFull)
}
