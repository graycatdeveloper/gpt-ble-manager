package gpt.ble.manager.sample

/** Run without Bluetooth access: --console-check checks the actual output stream encodings. */
internal fun printConsoleEncoding() {
    println("file.encoding=${System.getProperty("file.encoding")}")
    println("stdout=${System.out.charset()}, stderr=${System.err.charset()}")
    // A supplementary Unicode character checks both streams without localized messages.
    val fixture = "Unicode check: battery \uD83D\uDD0B"
    println(fixture)
    System.err.println(fixture)
}
