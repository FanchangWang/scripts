// PP-OCRv6 官方 OCR SDK（源自 PaddleOCR deploy/ppocr-android/ppocr-sdk，Apache-2.0）
// 依赖已对齐本项目版本目录：OpenCV 4.11.0（org.opencv）/ onnxruntime 1.29.0 / Kotlin 2.2.10
// 注意：SDK 上游锁定 OpenCV 4.5.3 + ORT 1.21.1，本组合属官方未测，需真机冒烟验证。
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.paddle.ocr"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            consumerProguardFiles("proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation(libs.onnxruntime.android)
    implementation(libs.opencv)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
}
