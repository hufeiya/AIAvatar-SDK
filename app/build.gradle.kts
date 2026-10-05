plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.neethu.aiavatar_sdk"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.neethu.aiavatar_sdk"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // demo 项目:release 直接用 debug 签名(免配 keystore,可装可调试对比)
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":corelib"))
    implementation(project(":avatar-orchestrator"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    // 视频模式：CameraX（预览+分析）与 ML Kit 人脸检测（bundled，含模型）
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.face.detection)
    // 猜拳 P2：MediaPipe GestureRecognizer 端侧手势（bundled 模型 assets/gesture_recognizer.task）
    implementation(libs.mediapipe.tasks.vision)
    // 预置卡导入映射/提示词覆盖的纯 JSON 逻辑（JVM 单测可跑；android.jar 的
    // org.json 是抛 "not mocked" 的桩，进不了单测）
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    // StringsI18nTest 反射扫 Strings 全部属性做「EN 无中文」不变量
    testImplementation(kotlin("reflect"))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}