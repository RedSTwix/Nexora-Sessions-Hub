pluginManagement {
    repositories {
        google()
        if (System.getenv("NEXORA_MAVEN_MIRROR") == "google") {
            maven("https://maven-central.storage-download.googleapis.com/maven2/") {
                name = "GoogleMavenCentral"
            }
        } else {
            mavenCentral()
        }
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        if (System.getenv("NEXORA_MAVEN_MIRROR") == "google") {
            maven("https://maven-central.storage-download.googleapis.com/maven2/") {
                name = "GoogleMavenCentral"
            }
        } else {
            mavenCentral()
        }
    }
}

rootProject.name = "Nexora Sessions Hub"
include(":app")
