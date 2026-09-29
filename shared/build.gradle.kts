import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
//    alias(libs.plugins.android.kotlin.multiplatform.library)//FIXME do not use!! can not access library !!
    alias(libs.plugins.android.library)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.compose.compiler)

    alias(libs.plugins.kotlin.serialization)

    alias(libs.plugins.ksp)

    alias(libs.plugins.buildConfig)
}

group = "idv.neo.ffmpeg.media.player"
version = "1.0-SNAPSHOT"

configurations.all {
    resolutionStrategy {
        cacheChangingModulesFor(0, "seconds")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    jvm(){
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_11)
                }
            }
        }
    }

//    // Target declarations - add or remove as needed below. These define
//    // which platforms this KMP module supports.
//    // See: https://kotlinlang.org/docs/multiplatform-discover-project.html#targets
//    androidLibrary {
////    android {
//        namespace = "idv.neo.ffmpeg.media.player.shared"
//        compileSdk = libs.versions.android.compileSdk.get().toInt()
//        minSdk = libs.versions.android.minSdk.get().toInt()
//
//        compilerOptions {
//            jvmTarget = JvmTarget.JVM_11
//        }
//        androidResources {
//            enable = true
//        }
//        withHostTest {
//            isIncludeAndroidResources = true
//        }
//        withDeviceTestBuilder {
//            sourceSetTreeName = "test"
//        }.configure {
//            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
//        }
//    }

    androidTarget {
        publishLibraryVariants("release", "debug")
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_1_8)
                }
            }
        }
//        @OptIn(ExperimentalKotlinGradlePluginApi::class)
//        compilerOptions {
//            jvmTarget.set(JvmTarget.JVM_1_8)
//        }
    }

    // For iOS targets, this is also where you should
    // configure native binary output. For more information, see:
    // https://kotlinlang.org/docs/multiplatform-build-native-binaries.html#build-xcframeworks

    // A step-by-step guide on how to include this library in an XCode
    // project can be found here:
    // https://developer.android.com/kotlin/multiplatform/migrate
//    listOf(
//        iosArm64(),
//        iosSimulatorArm64()
//    ).forEach { iosTarget ->
//        iosTarget.binaries.framework {
//            baseName = "Shared"
//            isStatic = true
//        }
//    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    targets.all {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    // 在 Kotlin 2.0+ 中，freeCompilerArgs 是 ListProperty
                    freeCompilerArgs.add("-Xexpect-actual-classes")
                }
            }
        }
    }

    sourceSets {
        all {
            languageSettings {
                optIn("kotlin.ExperimentalMultiplatform")
                optIn("androidx.compose.material3.ExperimentalMaterial3Api")
                optIn("org.jetbrains.compose.resources.ExperimentalResourceApi")
            }
        }
        commonMain.dependencies {
            implementation(libs.kotlin.stdlib)

            implementation(libs.jetbrains.compose.ui)
            implementation(libs.jetbrains.compose.components.resources)
            implementation(libs.jetbrains.compose.runtime)
            implementation(libs.jetbrains.compose.foundation)
            implementation(libs.jetbrains.compose.material)
            implementation(libs.jetbrains.compose.material3)
            implementation(libs.jetbrains.compose.material.icons.extended)

            implementation(libs.jetbrains.compose.ui.tooling.preview )

            implementation(libs.jetbrains.androidx.lifecycle.viewmodel)
            implementation(libs.jetbrains.androidx.lifecycle.viewmodel.compose)
            implementation(libs.jetbrains.androidx.lifecycle.runtime.compose)
            implementation(libs.jetbrains.androidx.navigation.compose)

            compileOnly("com.github.cybernhl.media:lib-common-lite:727538c430")
            // JitPack 遠端依賴 (Group ID 規則: com.github.CMingTseng.JavaCvPlayer，快取於 ~/.gradle/caches/modules-2/files-2.1/com.github.CMingTseng.JavaCvPlayer/)
            // 本地源碼發布使用 Group ID: idv.neo.ffmpeg.media.player，路徑: ~/.m2/repository/idv/neo/ffmpeg/media/player/
            // 切換為本地源碼開發請註解遠端依賴並解開 api(project(...))
            // api(project(":core"))
            api("com.github.CMingTseng.JavaCvPlayer:core:v1.0.2")
            // api(project(":core_ui_compose"))
            api("com.github.CMingTseng.JavaCvPlayer:core_ui_compose:v1.0.2")
            api("com.github.cybernhl.media:lib-ui-compose:727538c430")
            api("com.github.cybernhl.media:lib-ui-compose-material3:727538c430")
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }

        androidMain.dependencies {
            implementation(libs.jetbrains.compose.ui.tooling)
            implementation(libs.jetbrains.compose.ui.tooling.preview)

            //Exoplayer
            api(libs.media3.common)
            api(libs.media3.common.ktx)
            api(libs.media3.exoplayer)
            api(libs.media3.datasource.okhttp)
        }

        jvmMain.dependencies {
            implementation("com.github.cybernhl.media:lib-common-lite:727538c430")
            // implementation(project(":core-video-skia"))
            implementation("com.github.CMingTseng.JavaCvPlayer:core-video-skia:v1.0.2")
            implementation(libs.org.bytedeco.javacv.platform)
            implementation(libs.org.bytedeco.ffmpeg.platform.gpl)

            implementation(libs.org.openjfx.javafx.graphics)
        }
    }
}

android {
    namespace = "idv.neo.ffmpeg.media.player"
    compileSdk = 37

    buildFeatures {
        compose = true
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        disable.addAll(
            listOf(
                "MissingTranslation",
                "ExtraTranslation",
                "TypographyEllipsis",
                "UnspecifiedImmutableFlag",
                "UnusedResources",
                "TypographyDashes"
            )
        )

        // disable("MissingTranslation",
        //         "ExtraTranslation",
        //         "TypographyEllipsis",
        //         "UnspecifiedImmutableFlag",
        //         "UnusedResources",
        //         "TypographyDashes")
    }

    defaultConfig {
        minSdk = 23
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

//    packagingOptions
    packaging {
        resources {
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

dependencies {
//    androidRuntimeClasspath(libs.jetbrains.compose.ui.tooling)
}

compose.resources {
    publicResClass = true
    packageOfResClass = "idv.neo.ffmpeg.media.player"
    generateResClass = always
}
