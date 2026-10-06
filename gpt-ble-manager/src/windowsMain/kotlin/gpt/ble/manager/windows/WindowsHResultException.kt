package gpt.ble.manager.windows

/** Native HRESULT retained as signed Int; render with toUInt() for its hexadecimal bit pattern. */
internal class WindowsHResultException(val hresult: Int, message: String) :
    IllegalStateException(message)
