import org.jetbrains.compose.compose
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
//    alias(libs.plugins.kotlin.multiplatform)
//    kotlin("multiplatform")
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.jetbrains.compose.hot.reload)
}

group = "idv.neo.ffmpeg.media.player.desktop"
version = "1.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

//java {
//    toolchain {
//        languageVersion.set(JavaLanguageVersion.of(17))
//    }
//}

kotlin {
    //// use multiplatform plugin
//    jvm {
//        compilations.all {
//            kotlinOptions.jvmTarget = "17"
//        }
//        withJava()
//    }
    jvmToolchain(17)
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
    sourceSets {
        //// use multiplatform plugin
        // val jvmMain by getting {
        val main by getting {
            dependencies {
//                implementation(project(":core"))
                implementation(project(":shared"))
//                implementation(compose.desktop.currentOs)// multiplatform is ok jvm must api at library
                implementation(libs.kotlinx.coroutines.swing)
            }
        }
        //// use multiplatform plugin
        //val jvmTest by getting
    }
}

//https://medium.com/@makeevrserg/compose-desktop-shadowjar-1cba3aba9a58
compose.desktop {
    application {
        mainClass = "idv.neo.ffmpeg.media.player.desktop.MainKt"
        jvmArgs += listOf("--add-opens", "java.base/java.lang=ALL-UNNAMED")
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "idv.neo.ffmpeg.media.player.desktop"
//            description = ""
            packageVersion = "1.0.0"
            println("JMODS Folder: ${compose.desktop.application.javaHome}/jmods/java.base.jmod")
            includeAllModules = false
        }
    }
}
