pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { mavenCentral() } }
rootProject.name = "maternote-package-format"
include(":model", ":codec", ":validator", ":cli", ":test-fixtures")
