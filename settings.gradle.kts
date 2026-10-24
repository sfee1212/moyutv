pluginManagement {
    repositories {
        mavenCentral()
        maven { url = uri("https://chaquo.com/maven") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven { url = uri("https://chaquo.com/maven") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "TvPlayer"
include(":app")
