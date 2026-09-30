pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "KlipperCompanion"
include(":app", ":domain", ":transport", ":printer-api", ":adapter-paxx", ":stock-u1-adapter", ":project-format", ":desktop", ":adapter-common", ":adapter-octoprint", ":adapter-prusa", ":adapter-bambu", ":adapter-elegoo", ":test-grid")
