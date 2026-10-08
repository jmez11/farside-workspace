plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.farside.workspace"
    compileSdk = 35

    configurations.all {
        exclude(group = "com.google.guava", module = "listenablefuture")
    }

    defaultConfig {
        applicationId = "com.farside.workspace"
        minSdk = 32 // Required for Meta Spatial SDK features (Android 12L)
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "com.farside.workspace.RdpResizeInstrumentation"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    
    ndkVersion = "26.1.10909125"
    packaging { jniLibs { useLegacyPackaging = true; keepDebugSymbols += "**/libcloudflared.so" } }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    
    // Meta VR Layout SDK
    implementation(platform("com.meta.metavrx:metavrx-bom:1.2026.0.0"))
    implementation("com.meta.metavrx.layout:layout-compose-compat")
    implementation("com.meta.metavrx.layout:layout-window-compose-compat")

    // Termux VT Emulator
    implementation(project(":terminal-view"))
    implementation(project(":terminal-emulator"))
    implementation(project(":termux-shared"))

    // SSHJ
    implementation("com.hierynomus:sshj:0.38.0")
}
