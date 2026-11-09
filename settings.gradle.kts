pluginManagement {
    repositories {
        // 全部用无过滤的源。原先 google{} 带 content 白名单，
        // 在插件解析时会先按白名单筛仓库，容易把 KSP 这类插件挡在外面。
        // 顺序：国内镜像优先（Actions 上访问官方源偶发 504），官方源兜底。
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        google()
        mavenCentral()
        gradlePluginPortal()
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