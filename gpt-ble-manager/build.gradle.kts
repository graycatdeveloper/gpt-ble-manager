import gpt.ble.manager.buildlogic.BuildWindowsNative
import gpt.ble.manager.buildlogic.FindCmake
import org.gradle.api.tasks.bundling.AbstractArchiveTask

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    `maven-publish`
}

val onWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
val nativeResources = layout.buildDirectory.dir("generated/windowsResources")
val prepareAndroidLicense =
    tasks.register<Sync>("prepareAndroidLicense") {
        from(rootProject.file("LICENSE")) {
            // Use a library-specific path: Android excludes generic META-INF/LICENSE files.
            into("META-INF/gpt-ble-manager")
        }
        into(layout.buildDirectory.dir("generated/androidLicenseResources"))
    }

// Includes both JVM artifacts and the source archives created by Kotlin/Android plugins.
tasks.withType<AbstractArchiveTask>().configureEach {
    from(rootProject.file("LICENSE")) {
        into("META-INF/gpt-ble-manager")
    }
}

val buildWindowsNative =
    tasks.register<BuildWindowsNative>("buildWindowsNative") {
        description = "Build Windows Native"
        onlyIf { onWindows }
        sourceDirectory.set(layout.projectDirectory.dir("src/windowsMain/cpp"))
        buildDirectory.set(layout.buildDirectory.dir("cmake/windows-x64"))
        destination.set(nativeResources.map { it.dir("natives/windows-x64") })
        cmakeExecutable.set(
            providers
                .gradleProperty("cmakeExecutable")
                .orElse(providers.environmentVariable("CMAKE_EXECUTABLE"))
                .orElse(providers.of(FindCmake::class) {})
        )
    }

kotlin {
    withSourcesJar(publish = true)
    jvmToolchain(libs.versions.jdk.get().toInt())
    android {
        namespace = "gpt.ble.manager"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        withHostTestBuilder {}.configure { isReturnDefaultValues = true }
        compilerOptions {
            jvmTarget.set(
                org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(libs.versions.jdk.get())
            )
        }
    }
    jvm("windows")
    sourceSets {
        commonMain.dependencies { api(libs.kotlinx.coroutines.core) }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        named("windowsMain") { resources.srcDir(nativeResources) }
        named("androidMain") { resources.srcDir(prepareAndroidLicense) }
    }
}

tasks.named("windowsProcessResources") { dependsOn(buildWindowsNative) }

publishing {
    publications.withType<MavenPublication>().configureEach {
        artifactId =
            if (name == "kotlinMultiplatform") {
                "gpt-ble-manager"
            } else {
                "gpt-ble-manager-${name.lowercase()}"
            }
        pom {
            name.set("gpt-ble-manager")
            description.set(
                "Kotlin Multiplatform BLE client for Windows and Android, developed with ChatGPT assistance"
            )
            licenses {
                license {
                    name.set("MIT License")
                    url.set("https://opensource.org/license/mit")
                    distribution.set("repo")
                }
            }
        }
    }
    repositories {
        maven {
            name = "localDirectory"
            url =
                uri(
                    providers
                        .gradleProperty("localRepositoryPath")
                        .orElse(
                            rootProject.layout.buildDirectory.dir("repository").map {
                                it.asFile.absolutePath
                            }
                        )
                        .get()
                )
        }
    }
}
