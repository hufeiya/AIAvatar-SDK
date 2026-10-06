plugins {
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
