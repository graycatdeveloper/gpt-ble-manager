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
    // file.encoding alone does not control stdout/stderr on Windows JDK 21.
    // https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/System.html
    applicationDefaultJvmArgs =
        listOf(
            "-Dfile.encoding=UTF-8",
            "-Dstdout.encoding=UTF-8",
            "-Dstderr.encoding=UTF-8",
        )
}
