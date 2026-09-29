plugins {
    application
    kotlin("jvm")
    id("org.openjfx.javafxplugin") version "0.1.0"
}

group = "idv.neo.ffmpeg.media.player.demo"
version = "1.0-SNAPSHOT"

javafx {
    version = "17"
    modules("javafx.controls", "javafx.graphics", "javafx.swing")
}

dependencies {
//    implementation(project(":lib-common-lite"))
    implementation("com.github.cybernhl.media:lib-common-lite:727538c430")
    // JitPack 遠端依賴 (Group ID: com.github.CMingTseng.JavaCvPlayer, 快取: ~/.gradle/caches/modules-2/files-2.1/com.github.CMingTseng.JavaCvPlayer/)
//     implementation(project(":core"))
    implementation("com.github.CMingTseng.JavaCvPlayer:core:v1.0.2")
//     implementation(project(":core-video-javafx"))
    implementation("com.github.CMingTseng.JavaCvPlayer:core-video-javafx:v1.0.2")
    implementation(libs.kotlinx.coroutines.javafx)
    implementation(libs.kermit)
    implementation(libs.org.bytedeco.javacv.platform)
    implementation(libs.org.bytedeco.ffmpeg.platform.gpl)
}

application {
    mainClass.set("idv.neo.ffmpeg.media.player.demo.javafx.JavaFxDemoKt")
}

tasks.withType<Copy> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}
