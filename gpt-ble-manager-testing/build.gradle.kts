import org.gradle.api.tasks.bundling.AbstractArchiveTask

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.maven.publish)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    jvm("windows")
    android {
        namespace = "gpt.ble.manager.testing"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        withHostTestBuilder {}.configure { isReturnDefaultValues = true }
    }
    sourceSets {
        commonMain.dependencies { api(project(":gpt-ble-manager")) }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

val onWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
val publishToCentral =
    providers.gradleProperty("publishToCentral").map(String::toBoolean).orElse(false)
val prepareAndroidLicense =
    tasks.register<Sync>("prepareAndroidLicense") {
        from(rootProject.file("LICENSE")) { into("META-INF/gpt-ble-manager") }
        into(layout.buildDirectory.dir("generated/androidLicenseResources"))
    }

kotlin.sourceSets.named("androidMain") { resources.srcDir(prepareAndroidLicense) }

tasks.withType<AbstractArchiveTask>().configureEach {
    from(rootProject.file("LICENSE")) { into("META-INF/gpt-ble-manager") }
}

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
                "gpt-ble-manager-testing"
            } else {
                "gpt-ble-manager-testing-${name.lowercase()}"
            }
        pom {
            name.set("gpt-ble-manager-testing")
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
