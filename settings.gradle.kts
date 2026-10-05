@file:Suppress("UnstableApiUsage")

pluginManagement {
    // Settings plugins are resolved before the generated libs accessors exist.
    // Read this version from the same catalog used by all project plugins.
    val catalog = file("gradle/libs.versions.toml").readText()
    val resolverVersion =
        Regex("""(?m)^foojay-resolver\s*=\s*"([^"]+)"""").find(catalog)?.groupValues?.get(1)
            ?: error("Missing foojay-resolver version in gradle/libs.versions.toml")
    plugins {
        id("org.gradle.toolchains.foojay-resolver-convention") version resolverVersion
    }

    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention")
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "gpt-ble-manager"

include(
    ":gpt-ble-manager",
    ":sample-windows",
)
