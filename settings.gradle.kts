pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://api.xposed.info/")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention").version("0.8.0")
}

rootProject.name = "OppoHeadsetHook"
include(":app")
