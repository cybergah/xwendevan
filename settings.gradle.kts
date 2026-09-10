pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        flatDir { dirs("app/libs") }
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "Xwendevan"
include(":app")
