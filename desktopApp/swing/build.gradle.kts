plugins {
    application
    kotlin("jvm")
}

group = "idv.neo.ffmpeg.media.player.demo"
version = "1.0-SNAPSHOT"

dependencies {
//    implementation(project(":lib-common-lite"))
    implementation("com.github.cybernhl.media:lib-common-lite:727538c430")
    implementation(project(":core"))
    implementation(project(":core-video-swing"))
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kermit)
    implementation(libs.org.bytedeco.javacv.platform)
    implementation(libs.org.bytedeco.ffmpeg.platform.gpl)
}

application {
    mainClass.set("idv.neo.ffmpeg.media.player.demo.swing.SwingDemoKt")
}

tasks.withType<Copy> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(11))
    }
}
