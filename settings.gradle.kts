pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Linphone SDK (SIP/RTP stack)
        maven { url = uri("https://linphone.org/maven_repository") }
    }
}
rootProject.name = "UniFiPhone"
include(":app")
