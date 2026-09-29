enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
pluginManagement {
    repositories {
        gradlePluginPortal()
        maven(url = "https://plugins.gradle.org/m2/")
        mavenCentral()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        maven {
            url = uri("https://oss.sonatype.org/content/repositories/snapshots")
            name = "SonatypeSnapshots"
            mavenContent {
                snapshotsOnly()
            }
        }
        maven(url = "https://maven.aliyun.com/repository/gradle-plugin/")
        maven(url = "https://maven.aliyun.com/repository/spring-plugin/")
        maven(url = "https://jitpack.io")
        maven(url = "https://s3.amazonaws.com/repo.commonsware.com")
        maven(url = "https://maven.pkg.jetbrains.space/public/p/compose/dev")
        maven(url = "https://api.xposed.info/")
        maven(url = "https://jogamp.org/deployment/maven")
        maven(url = "https://developer.huawei.com/repo/")
        maven(url = "https://raw.githubusercontent.com/cybernhl/maven-repository/master/")
        maven(url = "https://maven.aliyun.com/repository/jcenter")
        maven(url = "https://maven.aliyun.com/repository/public/")
        maven(url = "https://maven.aliyun.com/repository/spring/")
        maven(url = "https://maven.aliyun.com/repository/google/")
        maven(url = "https://maven.aliyun.com/repository/grails-core/")
        maven(url = "https://maven.aliyun.com/repository/apache-snapshots/")
        maven(url = "https://packages.jetbrains.team/maven/p/skija/maven")
    }
    plugins {

    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

val fullVersion = System.getProperty("java.version", "8.0.0")
val versionComponents = fullVersion
    .split(".")
    .take(2)
    .filter { it.isNotBlank() }
    .map { Integer.parseInt(it) }

val currentJdk = if (versionComponents[0] == 1) versionComponents[1] else versionComponents[0]


@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        google()
        maven {
            url = uri("https://oss.sonatype.org/content/repositories/snapshots")
            name = "SonatypeSnapshots"
            mavenContent {
                snapshotsOnly()
            }
        }
        maven(url = "https://jitpack.io")
        maven(url = "https://s3.amazonaws.com/repo.commonsware.com")
        maven(url = "https://maven.pkg.jetbrains.space/public/p/compose/dev")
        maven(url = "https://api.xposed.info/")
        maven(url = "https://jogamp.org/deployment/maven")
        maven(url = "https://raw.githubusercontent.com/cybernhl/maven-repository/master/")
        maven(url = "https://maven.aliyun.com/repository/jcenter")
        maven(url = "https://maven.aliyun.com/repository/public/")
        maven(url = "https://maven.aliyun.com/repository/spring/")
        maven(url = "https://maven.aliyun.com/repository/google/")
        maven(url = "https://packages.jetbrains.team/maven/p/skija/maven")
    }
}

rootProject.name = "JavaCvPlayerDemo"

apply(from = "gradle/include_utils.gradle.kts")
val includeExternalProject = extra["includeExternalProject"] as (String, String) -> Unit

// includeExternalProject(":lib-common-lite", "/media3_exoplyaer/libraries/common_lite")

// includeExternalProject(":lib-ui-compose", "/media3_exoplyaer/libraries/ui_compose")
// includeExternalProject(":lib-ui-compose-material3", "/media3_exoplyaer/libraries/ui_compose_material3")

// 切換說明：若需本機源碼開發，請解開下方 includeExternalProject 註解，並至各模組 build.gradle.kts 切換為 project(...) 本地依賴。
// 若使用 JitPack 遠端依賴 (com.github.CMingTseng.JavaCvPlayer:*:v1.0.2)，請保持下方 includeExternalProject 為註解狀態。
// includeExternalProject(":core", "/libraries/core")
// includeExternalProject(":core_ui_compose", "/libraries/core_ui_compose")

// includeExternalProject(":core-video-swing", "/libraries/core-video-swing")
// includeExternalProject(":core-video-javafx", "/libraries/core-video-javafx")
// includeExternalProject(":core-video-compose-jvm", "/libraries/core-video-compose-jvm")
// includeExternalProject(":core-video-skia", "/libraries/core-video-skia")

// includeExternalProject(":core-video-android", "/libraries/core-video-android")

include(":shared")

include(":desktopApp:compose")
include(":desktopApp:swing")
include(":desktopApp:javafx")
