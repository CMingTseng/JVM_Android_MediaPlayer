import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    // JitPack 遠端依賴 (Group ID 規則: com.github.CMingTseng.JavaCvPlayer，快取於 ~/.gradle/caches/modules-2/files-2.1/com.github.CMingTseng.JavaCvPlayer/)
    // 本地源碼發布使用 Group ID: idv.neo.ffmpeg.media.player，路徑: ~/.m2/repository/idv/neo/ffmpeg/media/player/
    // 切換為本地源碼開發請註解遠端依賴並解開 implementation(project(...))
//     implementation(project(":core"))
    implementation("com.github.CMingTseng.JavaCvPlayer:core:v1.0.2")
//     implementation(project(":core-video-compose-jvm"))
    implementation("com.github.CMingTseng.JavaCvPlayer:core-video-compose-jvm:v1.0.2")
//    implementation(project(":lib-common-lite"))
    implementation("com.github.cybernhl.media:lib-common-lite:727538c430")
//     implementation(project(":core-video-skia"))
    implementation("com.github.CMingTseng.JavaCvPlayer:core-video-skia:v1.0.2")
//     implementation(project(":core_ui_compose"))
    implementation("com.github.CMingTseng.JavaCvPlayer:core_ui_compose:v1.0.2")
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
