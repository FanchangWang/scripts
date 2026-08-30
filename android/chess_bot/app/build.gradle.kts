plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    signingConfigs {
        getByName("debug") {
            storeFile = file("../jks/chess_bot.jks")
            storePassword = "chess_bot123"
            keyPassword = "chess_bot123"
            keyAlias = "chess_bot"
        }
    }
    namespace = "com.chess.bot"
    compileSdk {
        version = release(37)
    }
    // 开局库 .obk 与引擎权重 .nnue 体积大，存为不压缩资产：
    // 拷贝更快、且 openFd().length 可取精确字节长度用于换库检测
    androidResources {
        noCompress += listOf("obk", "nnue")
    }

    defaultConfig {
        applicationId = "com.chess.bot"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            //noinspection ChromeOsAbiSupport
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    packaging {
        // 强制解压 native 库到磁盘：libpikafish.so 需要作为可执行文件被 ProcessBuilder 启动
        jniLibs {
            useLegacyPackaging = true
        }
    }
    testOptions {
        // JVM 单测中 LogBus 依赖 android.util.Log，未 mock 时返回默认值而非抛 "not mocked"
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.opencv)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
