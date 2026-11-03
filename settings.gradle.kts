pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // RootEncoder (RTMP push, Apache-2.0) is published on JitPack only.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "FeiyuLive"
include(":app")
