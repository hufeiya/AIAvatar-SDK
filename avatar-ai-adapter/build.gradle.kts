plugins {
    id("maven-publish")
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}


java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    // Public API surface: Flow (coroutines), JsonObject (LlmConfig.extraBody)
    // and OkHttpClient (adapter constructor defaults) all leak into signatures.
    api(libs.kotlinx.coroutines.core)
    api(libs.okhttp)
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}

// ── Maven Central 发布（README Roadmap ①；坐标/签名/上传见 corelib 同款块与
// tools/publish-central.py）─────────────────────────────────────────────
java {
    withSourcesJar()
}

publishing {
    repositories {
        maven { name = "local"; url = uri(rootProject.layout.buildDirectory.dir("sdk-maven")) }
    }
    publications {
        create<MavenPublication>("release") {
            from(components["java"])
            groupId = property("SDK_GROUP") as String
            artifactId = "avatar-ai-adapter"
            version = property("SDK_VERSION") as String
            pom {
                name.set("AIAvatar AI adapters")
                description.set(
                    "OpenAI-compatible LLM/TTS/ASR adapters, Edge-TTS, Volcano Engine " +
                        "seed-tts WebSocket adapter and lip-sync DSP of the AIAvatar SDK."
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
