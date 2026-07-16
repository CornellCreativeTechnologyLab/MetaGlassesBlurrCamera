pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
val localProperties = java.util.Properties().apply {
    val localPropsFile = File(rootDir, "local.properties")
    if (localPropsFile.exists()) {
        localPropsFile.inputStream().use { load(it) }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/facebook/meta-wearables-dat-android")
            credentials {
                username = "x-access-token"
                password = providers.gradleProperty("github_token").orNull
                    ?: System.getenv("GITHUB_TOKEN")
                    ?: localProperties.getProperty("github_token")
            }
        }

        // Meta Wearables Device Access Toolkit is distributed through a private GitHub Packages Maven source.
        // Provide GITHUB_TOKEN in your environment or add github_token to local.properties.
    }
}

rootProject.name = "MetaGlasses"
include(":app")
