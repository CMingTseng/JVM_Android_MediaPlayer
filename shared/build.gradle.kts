import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.jetbrains.compose.hot.reload )
}

group = "idv.neo.ffmpeg.media.player"
version = "1.0-SNAPSHOT"

configurations.all {
    resolutionStrategy.cacheChangingModulesFor(0, "seconds")
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    jvm(){
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilations.all {
            compilerOptions.configure {
                jvmTarget.set(JvmTarget.JVM_11)
            }
        }
    }
    androidTarget {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_11)
                }
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.animationGraphics)
            implementation(compose.animation)
            implementation(compose.material)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)

            implementation(libs.jetbrains.androidx.lifecycle.viewmodel)
            implementation(libs.jetbrains.androidx.lifecycle.viewmodel.compose)
            implementation(libs.jetbrains.androidx.lifecycle.runtime.compose)

//            implementation(projects.core)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }

        jvmMain.dependencies {
            api(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.org.bytedeco.javacv.platform)
            implementation(libs.org.bytedeco.ffmpeg.platform.gpl)
//            implementation(libs.org.bytedeco.opencv.platform.gpu)

            val javafxVersion = "17.0.10" // Or use a variable from gradle.properties or libs.versions.toml
            implementation("org.openjfx:javafx-graphics:${javafxVersion}:win") // Example for Windows
            implementation("org.openjfx:javafx-graphics:${javafxVersion}:mac")  // Example for macOS (Intel)
            implementation("org.openjfx:javafx-graphics:${javafxVersion}:mac-aarch64")  // Example for macOS (Apple Silicon)
            implementation("org.openjfx:javafx-graphics:${javafxVersion}:linux")

        }
        androidMain.dependencies {
            implementation(libs.org.bytedeco.javacv.platform)
            implementation(libs.org.bytedeco.ffmpeg.platform.gpl)
//            implementation(libs.org.bytedeco.opencv.platform.gpu)
        }
    }
}

android {
    namespace = "idv.neo.ffmpeg.media.player"
    compileSdk = 36
    defaultConfig {
        minSdk = 21
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "idv.neo.ffmpeg.media.player"
    generateResClass = always
}
