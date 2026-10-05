package gpt.ble.manager.windows.jni

import java.nio.file.Files

/**
 * Один раз извлекает DLL из JAR во временный файл. Флаг выставляется только после успешного
 * System.load; @Synchronized защищает параллельное создание менеджеров.
 *
 * @see <a
 *   href="https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/System.html#load(java.lang.String)">System.load</a>
 */
internal object NativeLibrary {
    private var loaded = false

    @Synchronized
    fun load() {
        if (loaded) {
            return
        }
        require(System.getProperty("os.name").startsWith("Windows", true)) {
            "WindowsBleManager requires Windows"
        }
        require(System.getProperty("os.arch") in setOf("amd64", "x86_64")) {
            "This build contains the Windows x64 native library"
        }
        val stream =
            NativeLibrary::class
                .java
                .getResourceAsStream("/natives/windows-x64/gpt-ble-windows.dll")
                ?: throw UnsatisfiedLinkError(
                    "gpt-ble-windows.dll is missing; build the Windows artifact on Windows"
                )
        val dll = Files.createTempFile("gpt-ble-windows-", ".dll").toFile()
        dll.deleteOnExit()
        stream.use { input -> dll.outputStream().use { input.copyTo(it) } }
        System.load(dll.absolutePath)
        loaded = true
    }
}
