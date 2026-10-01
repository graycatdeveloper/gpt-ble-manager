plugins {
    alias(libs.plugins.kotlin.jvm);
    application
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

dependencies {
    implementation(project(":ble-manager"))
}

application {
    mainClass.set("dev.gpt.ble.sample.MainKt")
}
