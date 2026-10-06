package gpt.ble.manager.windows

import gpt.ble.manager.windows.jni.NativeLibrary

/**
 * JNI contract with C++: the fully qualified JVM class name, external signatures, and private
 * callback names must match NativeBridge.cpp and Registry.cpp. A callback may arrive on a native
 * thread; JNIEnv/global reference ownership and exception translation are handled in C++.
 *
 * @see <a href="https://docs.oracle.com/en/java/javase/21/docs/specs/jni/design.html">JNI
 *   design</a>
 */
internal class NativeBridge(private val owner: WindowsBleManager) {
    init {
        NativeLibrary.load()
    }

    external fun supportsPreferredParameters(): Boolean

    external fun preferredParameters(manager: Long, connection: Long, mode: Int): Int

    external fun create(): Long

    external fun destroy(manager: Long)

    external fun adapterState(manager: Long): Int

    external fun pairingState(manager: Long, address: String, addressType: Int): Int

    external fun pair(manager: Long, address: String, addressType: Int, timeoutMillis: Long): Int

    external fun unpair(manager: Long, address: String, addressType: Int, timeoutMillis: Long): Int

    external fun startScan(manager: Long, generation: Long)

    external fun stopScan(manager: Long)

    external fun connect(
        manager: Long,
        address: String,
        addressType: Int,
        timeoutMillis: Long,
    ): Long

    external fun monitor(manager: Long, connection: Long): Boolean

    external fun disconnect(manager: Long, connection: Long)

    external fun discover(manager: Long, connection: Long, timeoutMillis: Long): Array<String>

    external fun readDeviceName(manager: Long, connection: Long, timeoutMillis: Long): ByteArray?

    external fun read(
        manager: Long,
        connection: Long,
        attribute: Int,
        descriptor: Boolean,
        timeoutMillis: Long,
    ): ByteArray

    external fun write(
        manager: Long,
        connection: Long,
        attribute: Int,
        descriptor: Boolean,
        value: ByteArray,
        withResponse: Boolean,
        timeoutMillis: Long,
    )

    external fun subscribe(
        manager: Long,
        connection: Long,
        attribute: Int,
        mode: Int,
        timeoutMillis: Long,
    )

    external fun mtu(manager: Long, connection: Long): Int

    @Suppress("unused")
    private fun onAdvertisement(
        generation: Long,
        address: String,
        name: String?,
        rssi: Int,
        addressType: Int,
        connectable: Boolean,
        completeName: Boolean,
        services: Array<String>,
        manufacturer: Array<ByteArray>,
        serviceData: Array<ByteArray>,
    ) =
        owner.advertisement(
            generation,
            address,
            name,
            rssi,
            addressType,
            connectable,
            completeName,
            services,
            manufacturer,
            serviceData,
        )

    @Suppress("unused")
    private fun onScanStopped(generation: Long, error: String?) =
        owner.scanStopped(generation, error)

    @Suppress("unused")
    private fun onKnownDevice(generation: Long, address: String, name: String?) =
        owner.knownDevice(generation, address, name)

    @Suppress("unused")
    private fun onNameLookupFailed(generation: Long, message: String) =
        owner.nameLookupFailed(generation, message)

    @Suppress("unused")
    private fun onDisconnected(connection: Long, error: String) =
        owner.disconnected(connection, error)

    @Suppress("unused")
    private fun onNotification(connection: Long, attribute: Int, value: ByteArray) =
        owner.notification(connection, attribute, value)

    @Suppress("unused")
    private fun onMtuChanged(connection: Long, mtu: Int) = owner.mtuChanged(connection, mtu)

    @Suppress("unused") private fun onAdapterStateChanged(state: Int) = owner.adapterChanged(state)
}
