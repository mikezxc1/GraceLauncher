pluginManagement {
    repositories {
        // 阿里云放第一位：kotlin-compiler-embeddable 这类构件在官方
        // repo.maven.apache.org 上是 404，只在镜像里有。
        // 官方源随后兜底；Gradle 按顺序找，命中即停。
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    resolutionStrategy {
        eachPlugin {
            // KSP 的插件标记构件只有 pom、没有 .module。
            // Gradle 9 解析插件标记时优先找 .module，找不到就判定该版本不存在，
            // 于是所有仓库都试过仍报「plugin not found」。
            // 这里把插件 ID 直接重定向到真正的实现构件，绕过标记文件。
            if (requested.id.id == "com.google.devtools.ksp") {
                useModule("com.google.devtools.ksp:symbol-processing-gradle-plugin:${requested.version}")
            }
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