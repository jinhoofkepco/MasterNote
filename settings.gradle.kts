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
    }
}

rootProject.name = "StudyInkKernel"
include(
    ":app",
    ":core:model",
    ":annotation:engine",
    ":annotation:storage",
    ":document:pdf-androidx",
    ":feature:reader",
    ":feature:progress",
    ":feature:teacher",
)
