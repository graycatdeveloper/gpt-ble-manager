# Windows diagnostics

WinRT errors are formatted in English, regardless of the Windows display language.
`FormatMessageW` requests en-US explicitly. If no English system description exists,
an English fallback is used; the library never substitutes the localized Windows text.
Each HRESULT failure includes an invariant `HRESULT 0x........` prefix and preserves
its numeric value in the native cause. In 0.3.0 it is also available through
`BleException.details.platformStatus`. GATT statuses and HRESULTs are different domains.

## Console encoding

On this Windows JDK 21 installation `file.encoding=UTF-8` coexists with
`stdout.encoding=Cp1251` and `stderr.encoding=Cp1251`. A console decoding those bytes
as UTF-8 shows replacement characters. JNI itself transfers strings as UTF-16 using
NewString; it does not convert device names through Windows-1251.

The sample's Gradle run and distribution launchers set:

```text
-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8
```

For a consuming application, add these options to its JVM run configuration or its
applicationDefaultJvmArgs/JavaExec task, and configure the console to decode UTF-8.
Changing org.gradle.jvmargs affects the Gradle daemon, not its child application JVM.
The library does not replace global System.out/System.err in the host application.
Original Unicode device names are preserved. This setting is unrelated to Android logcat.

Run a hardware-independent console check:

```powershell
.\gradlew.bat :sample-windows:run --args="--console-check"
```

It reports the actual stream charsets and prints an English fixture with a Unicode
symbol on both streams. Fixing message display does not fix a BLE write failure:
capture the readable HRESULT, write mode and payload length to diagnose that operation.
The previous replacement-character log cannot reliably reconstruct its original text.

References: [Java System streams](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/System.html),
[FormatMessageW](https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-formatmessagew),
[JNI NewString](https://docs.oracle.com/en/java/javase/21/docs/specs/jni/functions.html#newstring).
