import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.farside.workspace"
    compileSdk = 35

    // CI release signing: reads key.properties from the repo root (gitignored, never committed).
    // Local debug builds are unaffected when the file is absent.
    val keystorePropertiesFile = rootProject.file("key.properties")
    val keystoreProperties = Properties()
    if (keystorePropertiesFile.exists()) {
        FileInputStream(keystorePropertiesFile).use { keystoreProperties.load(it) }
    }
    // Tag builds pass -PversionName / -PversionCode; local builds keep the defaults below.
    val ciVersionCode = providers.gradleProperty("versionCode").orNull?.toIntOrNull()
    val ciVersionName = providers.gradleProperty("versionName").orNull

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
            }
        }
        // Shared debug keystore (committed at app/debug.keystore): every CI,
        // local, and try-build debug APK signs with the same cert, so
        // `pm install -r` works between them — including over the Quest tunnel
        // with no uninstall. Well-known android/android credentials: this is a
        // debug cert, never trusted for anything release.
        create("sharedDebug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    configurations.all {
        exclude(group = "com.google.guava", module = "listenablefuture")
    }

    defaultConfig {
        applicationId = "com.farside.workspace"
        minSdk = 32 // Required for Meta Spatial SDK features (Android 12L)
        targetSdk = 34 // Horizon OS is API 34; targeting higher only triggers a dashboard warning
        versionCode = ciVersionCode ?: 1
        versionName = ciVersionName ?: "1.0"
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

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    
    ndkVersion = "26.1.10909125"
    packaging {
        jniLibs { useLegacyPackaging = true; keepDebugSymbols += "**/libcloudflared.so" }
        resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        resources.merges += listOf("META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/NOTICE")
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("sharedDebug")
        }
        getByName("release") {
            // Signed only when key.properties is present (CI release job); otherwise the
            // default debug key is used so local release builds don't fail.
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            // Shrink unused code: strips unreachable library classes (e.g. Bouncy
            // Castle's EST module, flagged by store scanners for a trust-all
            // TrustManager nothing calls). First R8 run: on-device smoke test
            // (SSH/RDP/SFTP) required before shipping a minified build.
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

dependencies {
    androidTestImplementation("org.apache.sshd:sshd-core:2.12.1")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.compose.foundation:foundation:1.7.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    
    // Meta VR Layout SDK
    implementation(platform("com.meta.metavrx:metavrx-bom:1.2026.0.0"))
    implementation("com.meta.metavrx.layout:layout-compose-compat")
    implementation("com.meta.metavrx.layout:layout-window-compose-compat")
    // OS-provided types used to attach the key bank to its hosted strip window.
    compileOnly("com.meta.metavr:metavr-os-jsdk")

    // Termux VT Emulator
    implementation(project(":terminal-view"))
    implementation(project(":terminal-emulator"))
    implementation(project(":termux-shared"))

    // SSHJ
    implementation("com.hierynomus:sshj:0.38.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.75")
    testImplementation("junit:junit:4.13.2")
}
