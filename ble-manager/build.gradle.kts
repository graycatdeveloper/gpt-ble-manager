import dev.gpt.ble.build.BuildWindowsNative
import dev.gpt.ble.build.FindCmake

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    `maven-publish`
}

val onWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
val nativeResources = layout.buildDirectory.dir("generated/windowsResources")
val buildWindowsNative = tasks.register<BuildWindowsNative>("buildWindowsNative") {
    description = "Build Windows Native"
    onlyIf { onWindows }
    sourceDirectory.set(layout.projectDirectory.dir("src/windowsMain/cpp"))
    buildDirectory.set(layout.buildDirectory.dir("cmake"))
    destination.set(nativeResources.map { it.dir("natives/windows-x64") })
    cmakeExecutable.set(providers.gradleProperty("cmakeExecutable")
        .orElse(providers.environmentVariable("CMAKE_EXECUTABLE"))
        .orElse(providers.of(FindCmake::class) {}))
}

kotlin {
    withSourcesJar(publish = true)
    jvmToolchain(libs.versions.jdk.get().toInt())
    android {
        namespace = "dev.gpt.ble"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        withHostTestBuilder {}.configure { isReturnDefaultValues = true }
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(libs.versions.jdk.get())) }
    }
    jvm("windows")
    sourceSets {
        commonMain.dependencies { api(libs.kotlinx.coroutines.core) }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        named("windowsMain") { resources.srcDir(nativeResources) }
    }
}
tasks.named("windowsProcessResources") { dependsOn(buildWindowsNative) }
/*publishing.publications.withType<MavenPublication>().configureEach {
    artifactId = if (name == "kotlinMultiplatform") "gpt-ble-manager" else "gpt-ble-manager-${name.lowercase()}"
    pom { name.set("GPT BLE Manager"); description.set("Kotlin Multiplatform BLE client for Windows and Android") }
}*/
publishing {
    // Ваша текущая конфигурация переименования artifactId
    publications.withType<MavenPublication>().configureEach {
        artifactId = if (name == "kotlinMultiplatform")
            "gpt-ble-manager"
        else
            "gpt-ble-manager-${name.lowercase()}"
        pom {
            name.set("GPT BLE Manager")
            description.set("Kotlin Multiplatform BLE client for Windows and Android")
        }
    }
    // Добавляем локальную папку libs в качестве целевого репозитория
    repositories {
        maven {
            // Укажите путь к папке libs внутри вашего клиентского приложения
            // Например, если они лежат в одной папке: ../Имя_Проекта_Приложения/libs
            url = uri("../../mentaris-api/local-repo")
        }
    }
}
