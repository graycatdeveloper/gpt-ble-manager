plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.maven.publish) apply false
}

val libraryVersion = providers.gradleProperty("releaseVersion").orElse(libs.versions.ble.manager)
val libraryGroup = providers.gradleProperty("mavenGroup").orElse("gpt.ble.manager")

allprojects {
    group = libraryGroup.get()
    version = libraryVersion.get()
}
