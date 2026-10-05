@file:Suppress("MissingPermission", "DEPRECATION", "OVERRIDE_DEPRECATION")

package gpt.ble.manager.android.gatt

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.os.Build

/**
 * Адаптирует callbacks Android к одной ожидающей операции соединения. На API 33+ используются
 * value-параметры; legacy callback читается только до API 33, иначе один ответ мог бы завершить
 * операцию дважды. Проверка identity GATT у владельца.
 *
 * @see <a
 *   href="https://developer.android.com/reference/android/bluetooth/BluetoothGattCallback">BluetoothGattCallback</a>
 */
internal class AndroidGattCallback(private val owner: AndroidConnection) : BluetoothGattCallback() {
    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        owner.connectionStateChanged(gatt, status, newState)
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) =
        owner.complete(gatt, "discover", null, status)

    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
    ) {
        if (Build.VERSION.SDK_INT < 33) {
            owner.complete(
                gatt,
                "read",
                characteristic,
                status,
                characteristic.value ?: byteArrayOf(),
            )
        }
    }

    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ) = owner.complete(gatt, "read", characteristic, status, value)

    override fun onCharacteristicWrite(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
    ) = owner.complete(gatt, "write", characteristic, status)

    override fun onDescriptorRead(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        status: Int,
    ) {
        if (Build.VERSION.SDK_INT < 33) {
            owner.complete(
                gatt,
                "readDescriptor",
                descriptor,
                status,
                descriptor.value ?: byteArrayOf(),
            )
        }
    }

    override fun onDescriptorRead(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        status: Int,
        value: ByteArray,
    ) = owner.complete(gatt, "readDescriptor", descriptor, status, value)

    override fun onDescriptorWrite(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        status: Int,
    ) = owner.complete(gatt, "writeDescriptor", descriptor, status)

    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
        owner.mtuChanged(gatt, mtu, status)
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ) {
        if (Build.VERSION.SDK_INT < 33) {
            owner.changed(gatt, characteristic, characteristic.value ?: byteArrayOf())
        }
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) = owner.changed(gatt, characteristic, value)

    override fun onServiceChanged(gatt: BluetoothGatt) {
        owner.serviceChanged(gatt)
    }
}
