import com.android.build.gradle.api.ApkVariantOutput
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// ── 正式签名配置（根目录 keystore.properties 存在则启用）────────────────
// keystore.properties 里四行：storeFile / storePassword / keyAlias / keyPassword
// （该文件与密钥库 *.jks 都在 .gitignore，严禁入库）。文件缺失时回落 debug
// 签名——保持「克隆即跑」，assembleDebug/assembleRelease 均可装可跑，只是
// release 的签名不是正式的。签名变了包就换不了旧安装，正式发布签名一旦
// 上线请永久保管好密钥库。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseSigning = keystorePropsFile.exists() &&
    keystoreProps.getProperty("storeFile")?.isNotBlank() == true

android {
    namespace = "com.neethu.aiavatar_sdk"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.neethu.aiavatar_sdk"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                // demo 项目:无 keystore.properties 时回落 debug 签名(免配可装可调试对比)
                signingConfigs.getByName("debug")
            }
        }
    }
    // 产物重命名：app/build/outputs/apk/<variant>/AIAvatar-v<版本>-<variant>.apk
    applicationVariants.all {
        val variantName = this.name
        outputs.all {
            (this as? ApkVariantOutput)?.outputFileName =
                "AIAvatar-v${defaultConfig.versionName}-$variantName.apk"
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