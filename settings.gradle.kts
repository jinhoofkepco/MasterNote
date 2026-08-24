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
    ":backup:storage",
    ":library:data",
    ":monitor:core",
    ":monitor:telegram",
    ":monitor:render",
    ":sync:lan",
    ":document:pdf-androidx",
    ":feature:reader",
    ":feature:library",
)
