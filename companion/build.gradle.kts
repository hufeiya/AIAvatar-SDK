import com.android.build.gradle.api.ApkVariantOutput
import java.util.Properties

/**
 * `:companion` —— 产品本体模块（酒馆文档 §6 工程承载定案，2026-10-24）：
 * 独立 applicationId/应用名/版本线（与 SDK_VERSION、demo APK 脱钩），依赖
 * corelib/orchestrator 的**公开 API**（Kotlin internal 按模块隔离，护栏
 * 天然成立）。assets 直接共享 `:app` 的资产目录（monorepo 期零复制；
 * 拆库时再随资产一起迁走，docs/tavern-companion-feasibility.md §6.4）。
 */
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // 卡级绑定/预置卡映射的纯 JSON 逻辑（JVM 单测可跑）
    alias(libs.plugins.kotlin.serialization)
}

// 与 :app 同一套正式签名（keystore.properties 存在则启用，缺省回落 debug）
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseSigning = keystorePropsFile.exists() &&
    keystoreProps.getProperty("storeFile")?.isNotBlank() == true

android {
    namespace = "com.neethu.companion"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.neethu.companion"
        minSdk = 29
        targetSdk = 36
        // 产品版本线独立递增（§6.4），0.1.0 起，与 SDK_VERSION/demo APK 无关
        versionCode = 1
        versionName = "0.1.0"
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
                signingConfigs.getByName("debug")
            }
        }
    }
    // 产物重命名：companion/build/outputs/apk/<variant>/Companion-v<版本>-<variant>.apk
    applicationVariants.all {
        val variantName = this.name
        outputs.all {
            (this as? ApkVariantOutput)?.outputFileName =
                "Companion-v${defaultConfig.versionName}-$variantName.apk"
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
    sourceSets {
        // monorepo 期直接复用 :app 的演示资产（VRM/场景/动画/卡/自主体时刻表），
        // 零复制零冗余；拆独立仓库时把 assets 目录一并迁走即可
        getByName("main") {
            assets.srcDir("../app/src/main/assets")
        }
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
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.serialization.json)
    // G10 问候后台推送（酒馆 P3）：WorkManager 周期任务 + 通知
    implementation(libs.androidx.work.runtime)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
