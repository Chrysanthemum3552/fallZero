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

    // ── Product Flavors ──
    // standard: 일반 앱(핸드폰). IS_KIOSK=false → 기존 동작과 완전히 동일.
    // kiosk:    키오스크 환경(고정 거치 + USB 웹캠). IS_KIOSK=true → 키오스크 적응 활성.
    flavorDimensions += "mode"
    productFlavors {
        create("standard") {
            dimension = "mode"
            buildConfigField("boolean", "IS_KIOSK", "false")
        }
        create("kiosk") {
            dimension = "mode"
            buildConfigField("boolean", "IS_KIOSK", "true")
            versionNameSuffix = "-kiosk"
        }
    }

    buildTypes {
        release {
            // 시연용 릴리스: 디버그 키 서명(바로 설치) + debuggable=false(디버그 빌드보다 빠른 "진짜 릴리스").
            //   minify는 끔 — R8 최적화(메서드 인라이닝)가 MediaPipe Graph의 스택 추적("no caller found on the
            //   stack")을 깨뜨려 카메라/포즈 초기화가 크래시함(MediaPipe+R8 알려진 비호환). keep만으론 못 막음.
            //   (로그 strip은 minify에 의존하므로 함께 보류 — 로그는 logcat에만 가서 화면엔 안 보임.)
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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
        buildConfig = true
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
