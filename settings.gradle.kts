// Maestro Client — отдельный standalone VPN-клиент (аналог 4.11.2 по поведению,
// интерфейс Maestro). Срез 1: каркас + первая сборка. MaestroVPN 1.0.212 не трогается.
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
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "maestro-client"
include(":app")
