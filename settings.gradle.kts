pluginManagement {
    repositories {
        google()
        maven {
            url = uri("https://cache-redirector.jetbrains.com/maven-central")
            content { includeGroup("org.jetbrains.kotlin") }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven {
            url = uri("https://cache-redirector.jetbrains.com/maven-central")
            content { includeGroup("org.jetbrains.kotlin") }
        }
        mavenCentral()
    }
}
rootProject.name = "HebiLiveTranslate"
include(":app")
