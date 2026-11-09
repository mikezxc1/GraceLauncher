pluginManagement {
    repositories {
        // 官方源放前面：阿里云镜像在 Actions 上会偶发 502，
        // 一旦某个仓库返回错误，Gradle 会把整个仓库禁用并且不回落，
        // 依赖就全解析失败。所以镜像只能作为最后兜底。
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
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
        google()
        mavenCentral()
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
    }
}

rootProject.name = "Grace Launcher"
include(":app")