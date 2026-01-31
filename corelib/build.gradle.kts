import java.util.Properties

plugins {
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
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    
    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // Filament
    implementation("com.google.android.filament:filament-android:1.68.3")
    implementation("com.google.android.filament:gltfio-android:1.68.3")
    implementation("com.google.android.filament:filament-utils-android:1.68.3")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

// ===========================================================================
// Filament Material Compilation Tasks
// ===========================================================================
// 
// To use these tasks:
// 1. Download matc from Filament releases: https://github.com/google/filament/releases
//    (Choose the release matching your Filament version: 1.68.3)
// 2. Set matc.path in local.properties OR ensure matc is on your PATH
// 3. Run: ./gradlew :corelib:compileMaterials
//
// The tasks compile .mat files to .filamat for Android (OpenGL ES)
// ===========================================================================

val matcPath: String = run {
    // Check local.properties first
    val localProps = project.rootProject.file("local.properties")
    if (localProps.exists()) {
        val props = Properties()
        localProps.inputStream().use { stream -> props.load(stream) }
        val path = props.getProperty("matc.path")
        if (path != null) return@run path
    }
    // Fall back to PATH
    "matc"
}

tasks.register<Exec>("compileMToon") {
    group = "filament"
    description = "Compile MToon material for VRM toon shading"
    
    val materialsDir = file("src/main/materials")
    val outputDir = file("src/main/assets/materials")
    
    inputs.file("$materialsDir/mtoon.mat")
    outputs.file("$outputDir/mtoon.filamat")
    
    doFirst {
        outputDir.mkdirs()
        println("Compiling MToon material using matc: $matcPath")
    }
    
    commandLine(matcPath,
        "-p", "mobile",
        "-a", "opengl",
        "-o", "$outputDir/mtoon.filamat",
        "$materialsDir/mtoon.mat"
    )
    
    isIgnoreExitValue = false
}

tasks.register<Exec>("compileSimpleToon") {
    group = "filament"
    description = "Compile simple toon material"
    
    val materialsDir = file("src/main/materials")
    val outputDir = file("src/main/assets/materials")
    
    inputs.file("$materialsDir/simple_toon.mat")
    outputs.file("$outputDir/simple_toon.filamat")
    
    doFirst {
        outputDir.mkdirs()
        println("Compiling simple toon material using matc: $matcPath")
    }
    
    commandLine(matcPath,
        "-p", "mobile",
        "-a", "opengl",
        "-o", "$outputDir/simple_toon.filamat",
        "$materialsDir/simple_toon.mat"
    )
    
    isIgnoreExitValue = false
}

tasks.register("compileMaterials") {
    group = "filament"
    description = "Compile all Filament materials"
    dependsOn("compileMToon", "compileSimpleToon")
}