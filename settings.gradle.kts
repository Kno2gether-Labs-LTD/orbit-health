pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
plugins {
    // Downloads the JDK 17 toolchain when it is not installed locally.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "OrbitHealth"
include(":ring-core", ":ring-ycbt", ":sync-protocol", ":server", ":app")
