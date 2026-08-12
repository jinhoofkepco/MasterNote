pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "maternote-studio"
include(":app", ":project", ":editor", ":assistant", ":exporter", ":preview")
includeBuild("../package-format")
