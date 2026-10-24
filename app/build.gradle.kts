plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

android {
    namespace = "com.iptv.tvplayer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.iptv.tvplayer.mybox"
        minSdk = 21
        targetSdk = 29
        versionCode = 127
        versionName = "1.2.7"
        multiDexEnabled = true
        
        ndk {
            abiFilters.clear()
            abiFilters.addAll(arrayOf("armeabi-v7a")) // Force 32-bit mode because P3P/P2P libraries lack arm64-v8a versions
        }

        lint {
            abortOnError = false
            disable.add("ExpiredTargetSdkVersion")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isDebuggable = false
            isJniDebuggable = false
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.4"
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

chaquopy {
    defaultConfig {
        // Python 3.10: 与本机已装的 3.10 解释器匹配 (buildPython), 且支持本项目强制的 32 位 armeabi-v7a
        version = "3.10"
        // 本机 Python 3.10 (用于构建期 pip 安装/字节码编译), 需与 version 同主次版本
        buildPython("C:/Python310/python.exe")
        pyc {
            src = false // 保留 .py 源码便于排错
        }
        pip {
            install("requests")
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.activity:activity-compose:1.8.2")
    
    // Compose
    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    
    // TV Compose
    implementation("androidx.tv:tv-foundation:1.0.0-alpha10")
    implementation("androidx.tv:tv-material:1.0.0-alpha10")

    // Classic Native UI
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Video Player (Locally compiled JNI + libmpv.so)

    // NanoHTTPD for local server
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Retrofit for EPG/M3U fetching
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-scalars:2.9.0")

    // ZXing for QR Code
    implementation("com.google.zxing:core:3.5.2")

    // WebKit for ProxyController
    implementation("androidx.webkit:webkit:1.11.0")

    // Media3 ExoPlayer
    implementation("androidx.media3:media3-exoplayer:1.2.0")
    implementation("androidx.media3:media3-ui:1.2.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.2.0")
    implementation("androidx.media3:media3-exoplayer-dash:1.2.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.2.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.media3:media3-datasource-rtmp:1.2.0")

    // JS Engine
    implementation("org.mozilla:rhino:1.7.13")

    // Tencent X5 Webview SDK
    implementation("com.tencent.tbs:tbssdk:44286")
}
