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
        // SDK de Onyx (onyxsdk-pen / onyxsdk-device). Sin este repo no resuelven.
        maven("https://repo.boox.com/repository/maven-public/")
    }
}

rootProject.name = "BooxCalendar"
include(":app")
