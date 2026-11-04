rootProject.name = "SwiftKey-Patches"

pluginManagement {
    repositories {
        // Vendored copy of the Morphe Patches Gradle plugin (v1.3.2, rebuilt from
        // https://github.com/MorpheApp/morphe-patches-gradle-plugin). This keeps the build
        // self-contained: no GitHub Packages authentication is required, so CI works with a
        // plain GITHUB_TOKEN or even without any credentials.
        maven { url = uri(settingsDir.resolve("vendor/maven-repo").toURI()) }
        gradlePluginPortal()
        google()
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/MorpheApp/registry")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")
            }
        }
        maven { url = uri("https://jitpack.io") }
    }
}

plugins {
    id("app.morphe.patches") version "1.3.2"
}

dependencyResolutionManagement {
    repositories {
        // Vendored app.morphe:morphe-patcher 1.5.2 (rebuilt from
        // https://github.com/MorpheApp/morphe-patcher tag v1.5.2) for the same reason as
        // above: it removes the GitHub Packages authentication requirement.
        maven { url = uri(settingsDir.resolve("vendor/maven-repo").toURI()) }
    }
}
