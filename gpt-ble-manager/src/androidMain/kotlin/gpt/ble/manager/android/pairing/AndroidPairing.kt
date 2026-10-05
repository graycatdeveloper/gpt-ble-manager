package gpt.ble.manager.android.pairing

import android.bluetooth.BluetoothDevice
import gpt.ble.manager.PairingState

internal fun androidPairingState(bondState: Int): PairingState =
    when (bondState) {
        BluetoothDevice.BOND_NONE -> PairingState.NotPaired
        BluetoothDevice.BOND_BONDING -> PairingState.Pairing
        BluetoothDevice.BOND_BONDED -> PairingState.Paired
        else -> PairingState.Unknown
    }
