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
        // Agora SDK
        maven { url = uri("https://repo1.maven.org/maven2") }
    }
}

rootProject.name = "EchoPanel"
include(":app")
