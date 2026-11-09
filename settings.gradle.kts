pluginManagement {
    repositories {
        // 全部用无过滤的源。原先 google{} 带 content 白名单，
        // 插件解析时会先按白名单筛仓库，容易把 KSP 这类插件挡在外面。
        // 顺序：国内镜像优先（Actions 访问官方源偶发 504），官方源兜底。
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    resolutionStrategy {
        eachPlugin {
            // KSP 的插件标记构件只有 pom，没有 .module。
            // Gradle 9 在解析插件标记时优先找 .module，找不到会判定该版本不存在，
            // 于是六个仓库全试过都报「plugin not found」。
            // 这里直接把插件 ID 重定向到真正的实现构件，绕过标记文件。
            if (requested.id.id == "com.google.devtools.ksp") {
                useModule("com.google.devtools.ksp:symbol-processing-gradle-plugin:${requested.version}")
            }
            // Room 的 Gradle 插件同理：标记构件不一定带 .module，
            // 直接指向 androidx.room 的实现构件。
            if (requested.id.id == "androidx.room") {
                useModule("androidx.room:room-gradle-plugin:${requested.version}")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        google()
        mavenCentral()
    }
}

rootProject.name = "Grace Launcher"
include(":app")