import gpt.ble.manager.buildlogic.BuildWindowsNative
import gpt.ble.manager.buildlogic.FindCmake
import org.gradle.api.tasks.bundling.AbstractArchiveTask

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.maven.publish)
}

val onWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
val publishToCentral =
    providers.gradleProperty("publishToCentral").map(String::toBoolean).orElse(false)
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

mavenPublishing {
    // Keep credentials and signing optional for normal builds and local publication.
    if (publishToCentral.get()) {
        require(onWindows) { "Build Central releases on Windows to include the native DLL." }
        require(providers.gradleProperty("mavenGroup").isPresent) {
            "Set -PmavenGroup to your verified Maven Central namespace."
        }
        require(!version.toString().endsWith("-local")) {
            "Set -PreleaseVersion to the version you intend to publish."
        }
        publishToMavenCentral(automaticRelease = false)
        signAllPublications()
    }
}

// Kotlin's default documentation archive is a placeholder; include the maintained guides.
tasks
    .withType<AbstractArchiveTask>()
    .matching { it.name.endsWith("JavadocJar") }
    .configureEach {
        from(rootProject.file("README.md"))
        from(rootProject.file("docs")) {
            into("docs")
            exclude("PUBLISHING.md")
        }
    }

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
            url.set("https://github.com/graycatdeveloper/gpt-ble-manager")
            inceptionYear.set("2026")
            licenses {
                license {
                    name.set("MIT License")
                    url.set("https://opensource.org/license/mit")
                    distribution.set("repo")
                }
            }
            developers {
                developer {
                    id.set("graycatdeveloper")
                    name.set("Gray Cat Developer")
                    url.set("https://github.com/graycatdeveloper")
                }
            }
            scm {
                url.set("https://github.com/graycatdeveloper/gpt-ble-manager")
                connection.set("scm:git:https://github.com/graycatdeveloper/gpt-ble-manager.git")
                developerConnection.set(
                    "scm:git:ssh://git@github.com/graycatdeveloper/gpt-ble-manager.git"
                )
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
