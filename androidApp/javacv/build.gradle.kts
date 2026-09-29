import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "idv.neo.ffmpeg.media.player.app"
    compileSdk = 37
    buildFeatures {
        compose = true
    }

    defaultConfig {
        applicationId = "idv.neo.ffmpeg.media.player.app"
        minSdk = 23
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        ndk {
            // 包含 64 位元、32 位元 ARM 以及開發用的 x86_64
//            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            abiFilters += listOf("arm64-v8a",  "x86_64")
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

    // [1. 針對手動分發 APK] 設定 APK 分離，這能讓各別架構的 APK 體積最小化
    splits {
        abi {
            isEnable = true
            reset()
//            include("arm64-v8a", "armeabi-v7a", "x86_64")
            include("arm64-v8a",   "x86_64")
            isUniversalApk = true // 產生一個包含所有架構的通用 APK (方便測試)
        }
    }

    // App Bundle : run  bundleRelease 時，Google Play auto
    bundle {
        abi {
            enableSplit = true // 根據 ABI (架構) 拆分
        }
        density {
            enableSplit = true // 根據螢幕密度拆分
        }
        language {
            enableSplit = true // 根據語言拆分
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/*"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/*.kotlin_module"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/io.netty.versions.properties"
            excludes += "META-INF/AL2.0"
            excludes += "META-INF/LGPL2.1"

            excludes += "lib/arm64-v8a/ffmpeg"
            excludes += "lib/arm64-v8a/ffprobe"
            excludes += "lib/arm64-v8a/ffplay"
            excludes += "lib/x86_64/ffmpeg"
            excludes += "lib/x86_64/ffprobe"
            excludes += "lib/x86_64/ffplay"

            pickFirsts += "**/ffprobe"
            pickFirsts += "**/ffmpeg"
            // 排除 JavaCPP 的屬性文件與 native-image 配置，減少體積並避免重複衝突
            excludes += "META-INF/native-image/**"
            excludes += "org/bytedeco/javacpp/properties/**"
        }
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += "lib/arm64-v8a/libavcodec.so"
            pickFirsts += "lib/arm64-v8a/libavdevice.so"
            pickFirsts += "lib/arm64-v8a/libavfilter.so"
            pickFirsts += "lib/arm64-v8a/libavformat.so"
            pickFirsts += "lib/arm64-v8a/libavutil.so"
            pickFirsts += "lib/arm64-v8a/libswresample.so"
            pickFirsts += "lib/arm64-v8a/libswscale.so"
            pickFirsts += "lib/arm64-v8a/libpostproc.so"
            pickFirsts += "lib/arm64-v8a/libjniavcodec.so"
            pickFirsts += "lib/arm64-v8a/libjniavdevice.so"
            pickFirsts += "lib/arm64-v8a/libjniavfilter.so"
            pickFirsts += "lib/arm64-v8a/libjniavformat.so"
            pickFirsts += "lib/arm64-v8a/libjniavutil.so"
            pickFirsts += "lib/arm64-v8a/libjniswresample.so"
            pickFirsts += "lib/arm64-v8a/libjniswscale.so"
            pickFirsts += "lib/arm64-v8a/libjnipostproc.so"
            pickFirsts += "lib/arm64-v8a/ffmpeg"
            pickFirsts += "lib/arm64-v8a/ffprobe"
            pickFirsts += "lib/arm64-v8a/ffplay"
            pickFirsts += "lib/x86_64/libavcodec.so"
            pickFirsts += "lib/x86_64/libavdevice.so"
            pickFirsts += "lib/x86_64/libavfilter.so"
            pickFirsts += "lib/x86_64/libavformat.so"
            pickFirsts += "lib/x86_64/libavutil.so"
            pickFirsts += "lib/x86_64/libswresample.so"
            pickFirsts += "lib/x86_64/libswscale.so"
            pickFirsts += "lib/x86_64/libpostproc.so"
            pickFirsts += "lib/x86_64/libjniavcodec.so"
            pickFirsts += "lib/x86_64/libjniavdevice.so"
            pickFirsts += "lib/x86_64/libjniavfilter.so"
            pickFirsts += "lib/x86_64/libjniavformat.so"
            pickFirsts += "lib/x86_64/libjniavutil.so"
            pickFirsts += "lib/x86_64/libjniswresample.so"
            pickFirsts += "lib/x86_64/libjniswscale.so"
            pickFirsts += "lib/x86_64/libjnipostproc.so"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_1_8
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":core-video-android"))
    implementation(project(":core_ui_compose"))
    implementation(project(":shared"))
    implementation("com.github.cybernhl.media:lib-common-lite:727538c430")
    implementation("com.github.cybernhl.media:lib-ui-compose-material3:727538c430")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.org.bytedeco.javacv) {
        exclude(group = "org.bytedeco", module = "opencv")
        exclude(group = "org.bytedeco", module = "openblas")
    }
    // 根據架構引入原生庫
    val ffmpegLib = libs.org.bytedeco.ffmpeg.pure.get()
    val ffmpegModule = ffmpegLib.module.toString()
    val ffmpegVersion = ffmpegLib.versionConstraint.requiredVersion

    val javacppLib = libs.org.bytedeco.javacpp.get()
    val javacppModule = javacppLib.module.toString()
    val javacppVersion = javacppLib.versionConstraint.requiredVersion

//    val platforms = listOf("android-arm64", "android-arm", "android-x86_64")
    val platforms = listOf("android-arm64", "android-x86_64")
    platforms.forEach { platform ->
        runtimeOnly("$ffmpegModule:$ffmpegVersion:$platform")
        runtimeOnly("$javacppModule:$javacppVersion:$platform")
    }
    /* 舊有的 Hardcoded 配置參考：
    // 1. 只引入 JavaCV 核心 Wrapper (不含 native)，並排除不必要的 OpenCV 與 OpenBLAS
    implementation("org.bytedeco:javacv:1.5.13") {
        exclude(group = "org.bytedeco", module = "opencv")
        exclude(group = "org.bytedeco", module = "openblas")
    }

    // 2. 使用 runtimeOnly 引入特定架構的原生庫，精確控制 APK 體積
    val ffmpegVersion = "6.1.1-1.5.10"
    val javacppVersion = "1.5.12"
    // android-arm 對應 armeabi-v7a
    val platforms = listOf("android-arm64", "android-arm", "android-x86_64")

    platforms.forEach { platform ->
        runtimeOnly("org.bytedeco:ffmpeg:$ffmpegVersion:$platform")
        runtimeOnly("org.bytedeco:javacpp:$javacppVersion:$platform")
    }
    */
}
