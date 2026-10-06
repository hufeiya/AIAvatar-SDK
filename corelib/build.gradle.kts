plugins {
    id("maven-publish")
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.neethu.corelib"
    compileSdk = 36

    defaultConfig {
        minSdk = 29

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake {
                cppFlags("")
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
        }
    }
    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
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

    implementation(libs.androidx.core.ktx)
    // （原 appcompat/material 两行依赖在 corelib 源码零引用，发布物不把它们
    //   强加给消费者，2026-10-07 发布审计时移除）
    // api：公开签名暴露这些库的类型——lifecycle 的 DefaultLifecycleObserver 是
    // AvatarSurfaceView 的父类型、compose 的 Modifier/Composable 在 AvatarView
    // 签名上、coroutines 的 StateFlow 在 AvatarController.state 上；implementation
    // 会让 Maven 消费者编译报 "Cannot access supertype"（0.1.0 消费者冒烟抓出）
    api(libs.androidx.lifecycle.runtime.ktx)
    api(libs.kotlinx.coroutines.core)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // Filament
    implementation("com.google.android.filament:filament-android:1.68.3")
    implementation("com.google.android.filament:gltfio-android:1.68.3")
    implementation("com.google.android.filament:filament-utils-android:1.68.3")
    
    // JSON parsing for VRM/glTF
    implementation("com.google.code.gson:gson:2.10.1")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
// ── Maven Central 发布（README Roadmap ①）──────────────────────────────
// 坐标统一在 gradle.properties（SDK_GROUP/SDK_VERSION/SDK_URL）；签名与校验
// 和、bundle 上传由 tools/publish-central.py 统一处理（gpg CLI + Central
// Portal API，上传不经 Gradle——release.central.sonatype.com 在本机代理下
// 不可达，central.sonatype.com 同源 API 可用）。
android {
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

afterEvaluate {
    publishing {
        repositories {
            maven { name = "local"; url = uri(rootProject.layout.buildDirectory.dir("sdk-maven")) }
        }
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = property("SDK_GROUP") as String
                artifactId = "corelib"
                version = property("SDK_VERSION") as String
                pom {
                    name.set("AIAvatar corelib")
                    description.set(
                        "Filament PBR rendering core of the AIAvatar SDK: VRM/GLB models, " +
                            "spring-bone physics, VRMA animation, expression/lip-sync driving, " +
                            "Compose and classic-View entry points."
                    )
                    url.set(property("SDK_URL") as String)
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                            distribution.set("repo")
                        }
                    }
                    developers {
                        developer {
                            id.set("hufeiya")
                            name.set("NeetHu")
                            url.set("https://github.com/hufeiya")
                        }
                    }
                    scm {
                        url.set(property("SDK_URL") as String)
                        connection.set("scm:git:git@github.com:hufeiya/AIAvatar-SDK.git")
                        developerConnection.set("scm:git:git@github.com:hufeiya/AIAvatar-SDK.git")
                    }
                }
            }
        }
    }
}
