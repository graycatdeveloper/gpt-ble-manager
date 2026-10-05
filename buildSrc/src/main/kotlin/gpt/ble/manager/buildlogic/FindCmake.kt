package gpt.ble.manager.buildlogic

import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import org.gradle.api.GradleException
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations

/**
 * Ленивый поиск CMake: PATH, стандартная установка, затем Visual Studio через vswhere. ValueSource
 * позволяет явно переопределить результат свойством или переменной окружения.
 *
 * @see <a
 *   href="https://docs.gradle.org/current/javadoc/org/gradle/api/provider/ValueSource.html">ValueSource</a>
 */
abstract class FindCmake : ValueSource<String, ValueSourceParameters.None> {
    @get:Inject abstract val exec: ExecOperations

    override fun obtain(): String {
        val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val executable =
            if (windows) {
                "cmake.exe"
            } else {
                "cmake"
            }
        System.getenv("PATH")
            .orEmpty()
            .split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .map { File(it.trim().trim('"'), executable) }
            .firstOrNull { it.isFile }
            ?.let {
                return it.absolutePath
            }

        if (windows) {
            val roots =
                listOfNotNull(
                        System.getenv("ProgramFiles"),
                        System.getenv("ProgramFiles(x86)"),
                        System.getenv("ProgramW6432"),
                    )
                    .distinct()
                    .map(::File)
            roots
                .map { it.resolve("CMake/bin/cmake.exe") }
                .firstOrNull { it.isFile }
                ?.let {
                    return it.absolutePath
                }

            // vswhere also discovers custom Visual Studio installation paths.
            val vswhere =
                roots
                    .map { it.resolve("Microsoft Visual Studio/Installer/vswhere.exe") }
                    .firstOrNull { it.isFile }
            if (vswhere != null) {
                val output = ByteArrayOutputStream()
                val result = exec.exec {
                    commandLine(
                        vswhere,
                        "-latest",
                        "-products",
                        "*",
                        "-requires",
                        "Microsoft.VisualStudio.Component.VC.CMake.Project",
                        "-utf8",
                        "-find",
                        "Common7/IDE/CommonExtensions/Microsoft/CMake/CMake/bin/cmake.exe",
                    )
                    standardOutput = output
                    isIgnoreExitValue = true
                }
                if (result.exitValue == 0) {
                    output
                        .toString(Charsets.UTF_8.name())
                        .lineSequence()
                        .map { File(it.trim().removePrefix("\uFEFF")) }
                        .firstOrNull { it.isFile }
                        ?.let {
                            return it.absolutePath
                        }
                }
            }
        }
        throw GradleException(
            "CMake was not found in PATH, a standard CMake installation, or Visual Studio. " +
                "Install CMake 3.20+ / Visual Studio C++ CMake tools, or set " +
                "-PcmakeExecutable=<path-to-cmake> or CMAKE_EXECUTABLE."
        )
    }
}
