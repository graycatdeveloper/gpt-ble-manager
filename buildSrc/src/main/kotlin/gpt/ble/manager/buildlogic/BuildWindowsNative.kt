package gpt.ble.manager.buildlogic

import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

/**
 * Собирает Windows x64 DLL и копирует её в ресурсы JVM-артефакта. Исходники и вложенные заголовки —
 * inputs, CMake cache — local state, DLL — output.
 *
 * @see <a href="https://docs.gradle.org/current/userguide/incremental_build.html">Incremental
 *   build</a>
 */
abstract class BuildWindowsNative @Inject constructor(private val exec: ExecOperations) :
    DefaultTask() {
    @get:Internal abstract val sourceDirectory: DirectoryProperty
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val sources
        get() =
            sourceDirectory.asFileTree.matching {
                // Native implementation is split into modules; nested headers must invalidate the
                // DLL too.
                include("src/**/*.cpp", "src/**/*.hpp", "tests/**/*.cpp", "CMakeLists.txt")
                exclude(".vs/**", ".idea/**", "out/**", "build/**", "cmake-build-*/**")
            }

    @get:LocalState abstract val buildDirectory: DirectoryProperty
    @get:OutputDirectory abstract val destination: DirectoryProperty
    @get:Input abstract val cmakeExecutable: Property<String>

    @TaskAction
    fun build() {
        val build = buildDirectory.get().asFile
        val cmake = cmakeExecutable.get()
        logger.lifecycle("Windows native build: CMake = $cmake")
        exec
            .exec {
                commandLine(
                    cmake,
                    "-S",
                    sourceDirectory.get().asFile,
                    "-B",
                    build,
                    "-A",
                    "x64",
                    "-DJAVA_HOME=${System.getProperty("java.home").replace('\\', '/')}",
                )
            }
            .assertNormalExitValue()
        exec
            .exec { commandLine(cmake, "--build", build, "--config", "Release", "--parallel") }
            .assertNormalExitValue()
        val target = destination.get().asFile.apply { mkdirs() }
        build
            .resolve("Release/gpt-ble-windows.dll")
            .copyTo(target.resolve("gpt-ble-windows.dll"), overwrite = true)
    }
}
