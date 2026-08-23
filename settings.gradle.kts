pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        // 国外可用镜像（GitHub 环境可访问）
        maven { name = "GoogleMavenCentralMirror"; url = uri("https://maven-central.storage-download.googleapis.com/maven2") }
        maven { name = "LegacyMavenCentral"; url = uri("https://repo1.maven.org/maven2") }
        maven { name = "JitPack"; url = uri("https://jitpack.io") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // 国外可用镜像（GitHub 环境可访问）
        maven { name = "GoogleMavenCentralMirror"; url = uri("https://maven-central.storage-download.googleapis.com/maven2") }
        maven { name = "LegacyMavenCentral"; url = uri("https://repo1.maven.org/maven2") }
        maven { name = "JitPack"; url = uri("https://jitpack.io") }
    }
}

rootProject.name = "blbl-android"
include(":app")

