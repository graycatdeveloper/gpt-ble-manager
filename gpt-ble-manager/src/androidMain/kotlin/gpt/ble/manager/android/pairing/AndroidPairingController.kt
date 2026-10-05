@file:Suppress("MissingPermission", "DEPRECATION")

package gpt.ble.manager.android.pairing

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.companion.CompanionDeviceManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import gpt.ble.manager.BleDevice
import gpt.ble.manager.BleError
import gpt.ble.manager.BleException
import gpt.ble.manager.PairResult
import gpt.ble.manager.PairingState
import gpt.ble.manager.UnpairResult
import gpt.ble.manager.android.gatt.AndroidConnection
import gpt.ble.manager.internal.canonicalAddress
import gpt.ble.manager.internal.pairing.BondOperation
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Connects createBond/removeBond to waiting for ACTION_BOND_STATE_CHANGED. Registers the listener
 * before reading the state snapshot and starting the operation so that a synchronous broadcast is
 * not lost. Shares the monitor with connect and close; Android owns the UI.
 *
 * @see <a
 *   href="https://developer.android.com/reference/android/bluetooth/BluetoothDevice#createBond()">createBond</a>
 * @see <a
 *   href="https://developer.android.com/reference/android/companion/CompanionDeviceManager#removeBond(int)">removeBond</a>
 */
internal class AndroidPairingController(
    private val context: Context,
    private val adapter: BluetoothAdapter?,
    private val closed: AtomicBoolean,
    private val lock: Any,
    private val connections: ConcurrentHashMap<String, AndroidConnection>,
) {
    private val bondOperations = ConcurrentHashMap<String, BondOperation>()

    fun hasPending(address: String): Boolean = bondOperations.containsKey(address)

    /** Called by manager.close under the shared monitor; unblocks waiters for bond broadcasts. */
    fun stopPending() {
        bondOperations.values.forEach {
            it.stop(BleException(BleError.Closed, "Manager is closed"))
        }
    }

    suspend fun getPairingState(device: BleDevice): PairingState =
        withContext(Dispatchers.IO) {
            if (closed.get()) {
                throw BleException(BleError.Closed, "Manager is closed")
            }
            val address = canonicalAddress(device.address)
            val bluetooth =
                adapter
                    ?: throw BleException(BleError.Unsupported, "Bluetooth adapter is unavailable")
            if (
                Build.VERSION.SDK_INT >= 31 &&
                    context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
                        PackageManager.PERMISSION_GRANTED
            ) {
                throw BleException(
                    BleError.PermissionDenied,
                    "BLUETOOTH_CONNECT permission is required to read pairing state",
                )
            }
            try {
                // Android may report BOND_NONE when Bluetooth is off: do not treat that as an
                // absent bond.
                val result =
                    if (!bluetooth.isEnabled) {
                        PairingState.Unknown
                    } else {
                        androidPairingState(bluetooth.getRemoteDevice(address).bondState)
                    }
                if (closed.get()) {
                    throw BleException(BleError.Closed, "Manager is closed")
                }
                result
            } catch (e: SecurityException) {
                throw BleException(
                    BleError.PermissionDenied,
                    "Unable to read Android pairing state",
                    e,
                )
            }
        }

    suspend fun pair(device: BleDevice, timeoutMillis: Long): PairResult =
        if (changePairing(device, timeoutMillis, PairingState.Paired)) {
            PairResult.Paired
        } else {
            PairResult.AlreadyPaired
        }

    suspend fun unpair(device: BleDevice, timeoutMillis: Long): UnpairResult =
        if (changePairing(device, timeoutMillis, PairingState.NotPaired)) {
            UnpairResult.Unpaired
        } else {
            UnpairResult.AlreadyUnpaired
        }

    private fun bondAdapter(): BluetoothAdapter {
        if (closed.get()) {
            throw BleException(BleError.Closed, "Manager is closed")
        }
        if (
            Build.VERSION.SDK_INT >= 31 &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
                    PackageManager.PERMISSION_GRANTED
        ) {
            throw BleException(
                BleError.PermissionDenied,
                "BLUETOOTH_CONNECT permission is required",
            )
        }
        return adapter
            ?: throw BleException(BleError.Unsupported, "Bluetooth adapter is unavailable")
    }

    private suspend fun changePairing(
        device: BleDevice,
        timeoutMillis: Long,
        target: PairingState,
    ): Boolean =
        withContext(Dispatchers.IO) {
            require(timeoutMillis in 1..120_000)
            val address = canonicalAddress(device.address)
            val bluetooth = bondAdapter()
            val operation = BondOperation(target)
            synchronized(lock) {
                if (closed.get()) {
                    throw BleException(BleError.Closed, "Manager is closed")
                }
                if (
                    connections.containsKey(address) ||
                        bondOperations.putIfAbsent(address, operation) != null
                ) {
                    throw BleException(
                        BleError.Rejected,
                        "Close the connection and wait for pending pairing operations for $address",
                    )
                }
            }
            try {
                val remote = bluetooth.getRemoteDevice(address)
                operation.execute(
                    timeoutMillis,
                    state = {
                        bondAdapter()
                        if (bluetooth.isEnabled) {
                            androidPairingState(remote.bondState)
                        } else {
                            PairingState.Unknown
                        }
                    },
                    listen = { changed ->
                        val listener =
                            object : BroadcastReceiver() {
                                override fun onReceive(context: Context?, intent: Intent?) {
                                    val stateChanged =
                                        intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED
                                    val deviceChanged =
                                        intent?.action ==
                                            BluetoothDevice.ACTION_BOND_STATE_CHANGED &&
                                            intent
                                                .getParcelableExtra<BluetoothDevice>(
                                                    BluetoothDevice.EXTRA_DEVICE
                                                )
                                                ?.address
                                                .equals(address, true)
                                    if (stateChanged || deviceChanged) {
                                        try {
                                            changed()
                                        } catch (e: BleException) {
                                            operation.stop(e)
                                        } catch (e: SecurityException) {
                                            operation.stop(
                                                BleException(
                                                    BleError.PermissionDenied,
                                                    "Bluetooth permission revoked",
                                                    e,
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        val filter =
                            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED).apply {
                                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                            }
                        if (Build.VERSION.SDK_INT >= 33) {
                            context.registerReceiver(listener, filter, Context.RECEIVER_EXPORTED)
                        } else {
                            context.registerReceiver(listener, filter)
                        }
                        val release: () -> Unit = { context.unregisterReceiver(listener) }
                        release
                    },
                    start = {
                        synchronized(lock) {
                            bondAdapter()
                            if (target == PairingState.Paired) {
                                remote.createBond()
                            } else {
                                if (Build.VERSION.SDK_INT < 36) {
                                    throw BleException(
                                        BleError.Unsupported,
                                        "Programmatic unpair requires Android API 36+. Use Bluetooth settings to forget the device.",
                                    )
                                }
                                val companion =
                                    context.getSystemService(CompanionDeviceManager::class.java)
                                        ?: throw BleException(
                                            BleError.Unsupported,
                                            "CompanionDeviceManager is unavailable; use Bluetooth settings",
                                        )
                                val association =
                                    companion.myAssociations.firstOrNull {
                                        it.deviceMacAddress?.toString().equals(address, true)
                                    }
                                        ?: throw BleException(
                                            BleError.Unsupported,
                                            "Unpair requires a CompanionDeviceManager association owned by this app; associate first or use Bluetooth settings",
                                        )
                                companion.removeBond(association.id)
                            }
                        }
                    },
                )
            } catch (e: SecurityException) {
                throw BleException(
                    BleError.PermissionDenied,
                    "Android denied the pairing operation",
                    e,
                )
            } finally {
                bondOperations.remove(address, operation)
            }
        }
}
