plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.yuanbao.promptfloat"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.yuanbao.promptfloat"
        minSdk = 26              // Android 8.0：TYPE_ACCESSIBILITY_OVERLAY / ACTION_PASTE 均可用
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug { isMinifyEnabled = false }
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// 刻意保持零第三方依赖：
// 悬浮窗、无障碍、存储全部用 Android 框架自带 API，CI 只需拉 AGP + Kotlin，
// 不引入 EasyFloat / AndroidX / Room 等，降低流水线下载失败与版本冲突的面。
dependencies { }
