package gpt.ble.manager.testing

import gpt.ble.manager.BleBytes
import gpt.ble.manager.BleDevice
import gpt.ble.manager.BleException
import gpt.ble.manager.BleUuid
import gpt.ble.manager.GattCharacteristic
import gpt.ble.manager.WriteMode

/** Immutable GATT templates; every connection assigns fresh session IDs and numeric handles. */
data class FakeDescriptor(val uuid: BleUuid, val value: BleBytes = BleBytes(byteArrayOf()))

data class FakeCharacteristic(
    val uuid: BleUuid,
    val properties: Int = 0x1e,
    val value: BleBytes = BleBytes(byteArrayOf()),
    val descriptors: List<FakeDescriptor> = emptyList(),
)

data class FakeService(val uuid: BleUuid, val characteristics: List<FakeCharacteristic>)

data class FakePeripheral(
    val device: BleDevice,
    val services: List<FakeService>,
    val mtu: Int = 23,
) {
    init {
        require(mtu in 23..517)
    }
}

/** A scripted delay uses coroutine time, so runTest can advance it without sleeping. */
data class FakeOutcome(val delayMillis: Long = 0, val error: BleException? = null) {
    init {
        require(delayMillis >= 0)
    }
}

data class FakeWrite(
    val characteristic: GattCharacteristic,
    val value: BleBytes,
    val mode: WriteMode,
)
