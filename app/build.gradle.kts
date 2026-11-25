plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.yuanbao.promptfloat"
    compileSdk = 34

    defaultConfig {
        // ★ 包名永久固定，任何版本都不许改：一改用户就必须卸载重装。
        // 覆盖安装失败的根因不是包名，而是 CI 每次随机生成 debug 签名（见下面 signingConfigs）。
        applicationId = "com.yuanbao.promptfloat"
        minSdk = 26              // Android 8.0：TYPE_ACCESSIBILITY_OVERLAY / ACTION_PASTE 均可用
        targetSdk = 34
        versionCode = 2
        versionName = "0.1.1"
    }

    /**
     * 固定签名。
     * GitHub Actions 每次都是全新 runner，默认的 ~/.android/debug.keystore 会重新随机生成，
     * 导致同一个包名的 APK 签名不同，Android 拒绝覆盖安装 → 用户被迫先卸载。
     * 这里把一把**公开的开发签名**提交进仓库，所有构建（本机 / CI / 任何 fork）都用同一把，
     * 于是 v0.1.1 可以直接盖在 v0.1.0 上装。
     *
     * 注意：这是开发签名，不是发布私钥，不提供任何安全保证。详见 SECURITY.md。
     */
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            // 同样使用固定签名，保证任何构建产物都能互相覆盖安装
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// 刻意保持零第三方依赖：
// 悬浮窗、无障碍、存储、日志全部用 Android 框架自带 API，CI 只需拉 AGP + Kotlin。
// 不引入 AndroidX / FileProvider / Room / Timber 等，降低流水线失败面与供应链面。
dependencies { }
