package dev.gpt.ble.android

import android.bluetooth.BluetoothDevice
import dev.gpt.ble.PairingState
import dev.gpt.ble.android.pairing.androidPairingState
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidPairingTest {
    @Test
    fun platformBondStatesIncludeUnknownFallback() {
        assertEquals(PairingState.NotPaired, androidPairingState(BluetoothDevice.BOND_NONE))
        assertEquals(PairingState.Pairing, androidPairingState(BluetoothDevice.BOND_BONDING))
        assertEquals(PairingState.Paired, androidPairingState(BluetoothDevice.BOND_BONDED))
        assertEquals(PairingState.Unknown, androidPairingState(BluetoothDevice.ERROR))
    }
}
