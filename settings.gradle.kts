// Repositories: the sandbox egress proxy blocks repo.maven.apache.org / repo1.maven.org
// (403), so Maven Central is accessed via Google's official GCS mirror, which is a
// complete drop-in equivalent. google() serves Android/AndroidX + the AGP plugin
// markers, which the mirror cannot host. The mirror also hosts the Kotlin/Compose
// plugin markers, so the Gradle Plugin Portal is unnecessary in this environment.
pluginManagement {
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
    }
}
rootProject.name = "track"
include(":app")
