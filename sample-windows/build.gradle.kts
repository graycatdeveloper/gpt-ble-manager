plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

dependencies {
    implementation(project(":gpt-ble-manager"))
}

application {
    mainClass.set("gpt.ble.manager.sample.MainKt")
}
