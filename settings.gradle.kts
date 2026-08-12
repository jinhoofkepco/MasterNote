pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

includeBuild("package-format")

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
    ":feature:library",
    ":feature:teacher",
    ":remote:protocol",
    ":remote:transport-api",
    ":remote:transport-nearby",
    ":remote:session",
    ":remote:storage",
    ":remote:sync",
    ":feature:remote-session",
    ":lab:remote-simulator",
    ":lab:assistant-webview",
)
