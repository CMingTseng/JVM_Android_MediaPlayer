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
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_1_8
    }
}

dependencies {
    implementation(project(":shared"))

    api(libs.media3.ui)
    api(libs.media3.common)
    api(libs.media3.common.ktx)
    api(libs.media3.exoplayer)
    api(libs.media3.datasource.okhttp)


    implementation(libs.androidx.activity.compose)

    implementation(libs.jetbrains.compose.ui.tooling.preview)
    debugImplementation(libs.jetbrains.compose.ui.tooling)
}

