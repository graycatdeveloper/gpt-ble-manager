plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
}
val libraryVersion = libs.versions.ble.manager.get()

allprojects {
    group = "dev.gpt.ble"
    version = libraryVersion
}
