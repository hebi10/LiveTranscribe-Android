pluginManagement {
    buildscript {
        repositories {
            google()
            mavenCentral()
        }
        dependencies {
            // Kotlin 2.2 metadata requires R8 8.10.21 or newer.
            classpath("com.android.tools:r8:8.10.21")
        }
    }
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
