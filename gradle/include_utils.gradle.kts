/**
 * 共用的動態引入外部專案工具函式
 * 
 * 使用方式：
 * 在 settings.gradle.kts 中加入：
 * apply(from = "gradle/include_utils.gradle.kts")
 * val includeExternalProject = extra["includeExternalProject"] as (String, String) -> Unit
 * includeExternalProject(":name", "/path/to/module")
 */

val includeExternalProject: (String, String) -> Unit = { name, subPath ->
    // 獲取 settings.gradle.kts 所在的目錄（根目錄）
    val rootDir = settings.settingsDir
    
    val localDir = File(rootDir, subPath.removePrefix("/"))
    val externalDir = File(rootDir.parentFile, subPath.removePrefix("/"))

    val finalDir = if (localDir.exists()) localDir else if (externalDir.exists()) externalDir else null

    if (finalDir != null) {
        include(name)
        project(name).projectDir = finalDir
        println("SUCCESS: Included '$name' from ${finalDir.absolutePath}")
    } else {
        println("WARNING: Directory for '$name' not found at ${localDir.absolutePath} or ${externalDir.absolutePath}")
    }
}

// 將函式放入 extra property 以便外部呼叫
extra.set("includeExternalProject", includeExternalProject)
