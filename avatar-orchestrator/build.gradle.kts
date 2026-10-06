plugins {
    id("maven-publish")
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.neethu.orchestrator"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    testOptions {
        // 主代码（AvatarSession/FaceDriver）在监听器常驻路径上有 android.util.Log，
        // JVM 单测没有 mock，return default values 让 Log 变 no-op 而不是抛 RuntimeException。
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    api(project(":avatar-ai-adapter"))
    // api：AvatarSession/AIAvatarSdk.ChatConfig 的公开签名暴露 corelib 类型
    // （AvatarController/Lang），Maven 消费者必须能传递解析到它
    api(project(":corelib"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    api(libs.androidx.room.runtime)
    api(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// ── Maven Central 发布（README Roadmap ①；坐标/签名/上传见 corelib 同款块与
// tools/publish-central.py）─────────────────────────────────────────────
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
                artifactId = "avatar-orchestrator"
                version = property("SDK_VERSION") as String
                pom {
                    name.set("AIAvatar orchestrator")
                    description.set(
                        "Conversational orchestration of the AIAvatar SDK: LLM streaming -> " +
                            "sentence chunking -> concurrent TTS -> ordered playback -> face " +
                            "driving, plus the AIAvatarSdk high-level facade."
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
