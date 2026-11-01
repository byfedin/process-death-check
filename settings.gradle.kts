pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google { content { includeGroupByRegex("com\\.android.*") } }
        mavenCentral()
    }
}

rootProject.name = "process-death-check"

include(":core", ":cli", ":e2e", ":gradle-plugin")
