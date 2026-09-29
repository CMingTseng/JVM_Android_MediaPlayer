import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.compose.compiler)
}

group = "idv.neo.ffmpeg.media.player.app"
version = "1.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":core-video-compose-jvm"))
//    implementation(project(":lib-common-lite"))
    implementation("com.github.cybernhl.media:lib-common-lite:727538c430")
    implementation(project(":core-video-skia"))
    implementation(project(":core_ui_compose"))
    implementation("com.github.cybernhl.media:lib-ui-compose-material3:727538c430")
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.org.bytedeco.javacv.platform)
    implementation(libs.org.bytedeco.ffmpeg.platform.gpl)
    implementation(libs.jetbrains.compose.ui.tooling.preview)
}

compose.desktop {
    application {
        mainClass = "idv.neo.ffmpeg.media.player.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "idv.neo.ffmpeg.media.player"
            packageVersion = "1.0.0"
        }
    }
}