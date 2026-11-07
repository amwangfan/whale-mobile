allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

val newBuildDir: Directory =
    rootProject.layout.buildDirectory
        .dir("../../build")
        .get()
rootProject.layout.buildDirectory.value(newBuildDir)

subprojects {
    val newSubprojectBuildDir: Directory = newBuildDir.dir(project.name)
    project.layout.buildDirectory.value(newSubprojectBuildDir)
}
subprojects {
    project.evaluationDependsOn(":app")
    // file_picker 8.3.7 自身硬编码 compileSdk 34，而 flutter_plugin_android_lifecycle
    // 用 flutter.compileSdkVersion（本工具默认 36）→ AAR 元数据检查冲突。
    // 统一把所有 Android 库插件压回 34（与 file_picker 一致；App 侧 36 无妨）。
    // 注意不能用 afterEvaluate + evaluationDependsOn(":app") 混用（:app 已评估会炸），
    // 这里只在库项目上注册。
    // 最终值以 afterEvaluate 为准：file_picker 自带 34 也被抬到 36。
    if (project.name != "app") {
        project.afterEvaluate {
            if (project.plugins.hasPlugin("com.android.library")) {
                project.extensions.getByType(
                    com.android.build.gradle.LibraryExtension::class.java
                ).compileSdk = 36
            }
        }
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
