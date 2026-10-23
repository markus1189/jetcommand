package link.thesymbolic.jetcommand.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import link.thesymbolic.jetcommand.protocol.BedJetTransport
import link.thesymbolic.jetcommand.protocol.Characteristic
import link.thesymbolic.jetcommand.protocol.TransportException
import link.thesymbolic.jetcommand.protocol.TransportException.Kind
import link.thesymbolic.jetcommand.protocol.TransportState
import link.thesymbolic.jetcommand.protocol.Uuids
import link.thesymbolic.jetcommand.protocol.toHex

/**
 * [BedJetTransport] over Android GATT. Every public method throws [TransportException] with kind
 * NOT_CONNECTED when BLUETOOTH_CONNECT is not granted, so callers need no permission plumbing.
 */
class GattTransport(context: Context, private val device: BluetoothDevice) : BedJetTransport {
    private val context = context.applicationContext

    private val _state = MutableStateFlow<TransportState>(TransportState.Disconnected)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    // Callbacks arrive on a binder thread and must never block; a slow collector loses the oldest
    // packets.
    private val _notifications =
        MutableSharedFlow<ByteArray>(
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    override val notifications: SharedFlow<ByteArray> = _notifications.asSharedFlow()

    private enum class Op {
        CONNECT,
        MTU,
        DISCOVER,
        DESCRIPTOR_WRITE,
        WRITE,
        READ,
    }

    private class Pending(val op: Op, val uuid: UUID? = null) {
        val result = CompletableDeferred<ByteArray>()
    }

    private val lock = Any()
    private var gatt: BluetoothGatt? = null
    private var pending: Pending? = null
    private var chars: Map<Characteristic, BluetoothGattCharacteristic> = emptyMap()

    /** Negotiated ATT MTU of the current connection. */
    var mtu = DEFAULT_MTU
        private set

    // checked by requirePermission(); a later revocation surfaces in await()
    @SuppressLint("MissingPermission")
    override suspend fun connect() {
        requirePermission()
        synchronized(lock) {
            check(gatt == null) { "connect() while already connected or connecting" }
            _state.value = TransportState.Connecting
        }
        try {
            await(Op.CONNECT) {
                synchronized(lock) {
                    // The replacement, connectGatt(BluetoothGattConnectionSettings, …), is API 37+.
                    @Suppress("DEPRECATION")
                    gatt =
                        device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                }
                gatt != null
            }
            val g = synchronized(lock) { gatt } ?: throw TransportException(Kind.DISCONNECTED)
            mtu =
                try {
                    await(Op.MTU) { g.requestMtu(REQUESTED_MTU) }.let { decodeInt(it) }
                } catch (e: TransportException) {
                    if (e.kind == Kind.DISCONNECTED) throw e
                    DEFAULT_MTU
                }
            await(Op.DISCOVER) { g.discoverServices() }
            val service =
                g.getService(UUID.fromString(Uuids.SERVICE))
                    ?: throw TransportException(
                        Kind.MISSING_CHARACTERISTIC,
                        message = "BedJet service not found",
                    )
            val found =
                Characteristic.entries
                    .mapNotNull { c ->
                        service.getCharacteristic(UUID.fromString(c.uuid))?.let { c to it }
                    }
                    .toMap()
            // Older firmware lacks REPLY_ALT; everything else is required.
            (Characteristic.entries - Characteristic.REPLY_ALT - found.keys).firstOrNull()?.let {
                throw TransportException(
                    Kind.MISSING_CHARACTERISTIC,
                    message = "Missing characteristic $it",
                )
            }
            synchronized(lock) { chars = found }
            enableNotifications(g, found.getValue(Characteristic.STATUS))
            _state.value = TransportState.Connected(mtu)
        } catch (e: Throwable) {
            closeGatt()
            _state.value = TransportState.Disconnected
            if (e is TransportException && e.kind == Kind.DISCONNECTED) {
                throw TransportException(Kind.CONNECT_FAILED, e.gattStatus)
            }
            throw e
        }
    }

    @SuppressLint("MissingPermission") // only called from connect(), after requirePermission()
    private suspend fun enableNotifications(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
        if (!g.setCharacteristicNotification(c, true))
            throw TransportException(
                Kind.CONNECT_FAILED,
                message = "setCharacteristicNotification failed",
            )
        val cccd =
            c.getDescriptor(CCCD)
                ?: throw TransportException(
                    Kind.MISSING_CHARACTERISTIC,
                    message = "STATUS has no CCCD",
                )
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        await(Op.DESCRIPTOR_WRITE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = value
                @Suppress("DEPRECATION") g.writeDescriptor(cccd)
            }
        }
    }

    override suspend fun disconnect() {
        closeGatt()
        _state.value = TransportState.Disconnected
    }

    // checked by requirePermission(); a later revocation surfaces in await()
    @SuppressLint("MissingPermission")
    override suspend fun write(characteristic: Characteristic, bytes: ByteArray) {
        require(characteristic == Characteristic.COMMAND || characteristic == Characteristic.NAME) {
            "$characteristic is not writable"
        }
        requirePermission()
        val (g, c) = target(characteristic)
        await(Op.WRITE, c.uuid) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                c.value = bytes
                @Suppress("DEPRECATION") g.writeCharacteristic(c)
            }
        }
    }

    // MissingPermission: checked by requirePermission(); a later revocation surfaces in await().
    // LogConditional: release builds log BLE traffic on purpose, for adb logcat on the phone.
    @SuppressLint("MissingPermission", "LogConditional")
    override suspend fun read(characteristic: Characteristic): ByteArray {
        requirePermission()
        val (g, c) = target(characteristic)
        return await(Op.READ, c.uuid) { g.readCharacteristic(c) }
            .also { Log.d(TAG, "read $characteristic ${it.size}: ${it.toHex()}") }
    }

    private fun target(
        characteristic: Characteristic
    ): Pair<BluetoothGatt, BluetoothGattCharacteristic> =
        synchronized(lock) {
            val g = gatt
            if (g == null || _state.value !is TransportState.Connected)
                throw TransportException(Kind.NOT_CONNECTED)
            val c =
                chars[characteristic]
                    ?: throw TransportException(
                        Kind.MISSING_CHARACTERISTIC,
                        message = "Missing characteristic $characteristic",
                    )
            g to c
        }

    /**
     * Registers [op] as the one in-flight operation, starts it and suspends until its callback.
     * [start] returning false means Android refused to queue it (busy stack, or link already gone).
     */
    private suspend fun await(op: Op, uuid: UUID? = null, start: () -> Boolean): ByteArray {
        val p = Pending(op, uuid)
        synchronized(lock) {
            // Overlapping GATT operations make Android silently drop one; better to fail the caller
            // loudly.
            check(pending == null) {
                "$op while ${pending!!.op} is in flight: GattTransport operations must be serialized"
            }
            pending = p
        }
        try {
            val started =
                try {
                    start()
                } catch (e: SecurityException) {
                    throw TransportException(
                        Kind.NOT_CONNECTED,
                        message = "BLUETOOTH_CONNECT revoked: ${e.message}",
                    )
                }
            if (!started)
                throw TransportException(
                    if (op == Op.CONNECT) Kind.CONNECT_FAILED else Kind.OPERATION_FAILED,
                    message = "$op could not be started",
                )
            return p.result.await()
        } finally {
            synchronized(lock) { if (pending === p) pending = null }
        }
    }

    private fun complete(op: Op, uuid: UUID?, status: Int, value: ByteArray = EMPTY) {
        val p =
            synchronized(lock) {
                pending?.takeIf { it.op == op && (it.uuid == null || it.uuid == uuid) }
            } ?: return
        if (status == BluetoothGatt.GATT_SUCCESS) p.result.complete(value)
        else p.result.completeExceptionally(failure(op, status))
    }

    @SuppressLint("MissingPermission") // close() needs no live permission to release our own client
    private fun closeGatt() {
        val (g, p) =
            synchronized(lock) {
                val g = gatt
                gatt = null
                chars = emptyMap()
                g to pending
            }
        // After close() no more callbacks arrive, so a pending op would hang forever.
        p?.result?.completeExceptionally(TransportException(Kind.DISCONNECTED))
        try {
            g?.disconnect()
            g?.close()
        } catch (_: SecurityException) {}
    }

    private fun requirePermission() {
        if (
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            throw TransportException(Kind.NOT_CONNECTED, message = "BLUETOOTH_CONNECT not granted")
        }
    }

    private val callback =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                // gatt is still null if this races connectGatt() returning.
                if (synchronized(lock) { gatt.let { it != null && it !== g } }) return
                if (
                    newState == BluetoothProfile.STATE_CONNECTED &&
                        status == BluetoothGatt.GATT_SUCCESS
                ) {
                    complete(Op.CONNECT, null, status)
                    return
                }
                if (
                    newState != BluetoothProfile.STATE_DISCONNECTED &&
                        status == BluetoothGatt.GATT_SUCCESS
                )
                    return
                val wasConnected = _state.value is TransportState.Connected
                val p = synchronized(lock) { pending }
                p?.result?.completeExceptionally(TransportException(Kind.DISCONNECTED, status))
                if (wasConnected) {
                    closeGatt()
                    _state.value =
                        TransportState.Lost(TransportException(Kind.DISCONNECTED, status))
                }
                // While connecting, connect()'s catch closes the gatt and resets the state.
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) =
                complete(Op.MTU, null, status, encodeInt(mtu))

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) =
                complete(Op.DISCOVER, null, status)

            override fun onDescriptorWrite(
                g: BluetoothGatt,
                d: BluetoothGattDescriptor,
                status: Int,
            ) = complete(Op.DESCRIPTOR_WRITE, null, status)

            override fun onCharacteristicWrite(
                g: BluetoothGatt,
                c: BluetoothGattCharacteristic,
                status: Int,
            ) = complete(Op.WRITE, c.uuid, status)

            // API 33+ calls only the value-carrying overloads (their default implementations
            // forward to the
            // deprecated ones, which we override for 31/32). Overriding both without super calls
            // means each
            // event is handled exactly once.
            override fun onCharacteristicRead(
                g: BluetoothGatt,
                c: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int,
            ) = complete(Op.READ, c.uuid, status, value)

            @Deprecated("API 31/32 only")
            override fun onCharacteristicRead(
                g: BluetoothGatt,
                c: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
                @Suppress("DEPRECATION")
                complete(Op.READ, c.uuid, status, c.value?.copyOf() ?: EMPTY)
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                c: BluetoothGattCharacteristic,
                value: ByteArray,
            ) = onNotification(c.uuid, value)

            @Deprecated("API 31/32 only")
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
                // Copy now: the shared characteristic's value is overwritten by the next event.
                @Suppress("DEPRECATION") c.value?.copyOf()?.let { onNotification(c.uuid, it) }
            }
        }

    @SuppressLint("LogConditional") // see read()
    private fun onNotification(uuid: UUID, value: ByteArray) {
        if (uuid != STATUS_UUID) return
        Log.d(TAG, "notify ${value.size}: ${value.toHex()}")
        _notifications.tryEmit(value)
    }

    companion object {
        private const val TAG = "GattTransport"
        const val REQUESTED_MTU = 247
        const val DEFAULT_MTU = 23
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val STATUS_UUID: UUID = UUID.fromString(Uuids.STATUS)
        private val EMPTY = ByteArray(0)

        private fun encodeInt(i: Int) = byteArrayOf((i shr 8).toByte(), i.toByte())

        private fun decodeInt(b: ByteArray) =
            ((b[0].toInt() and 0xff) shl 8) or (b[1].toInt() and 0xff)

        /** GATT [status] of a failed [op], as the contract's exception. */
        private fun failure(op: Op, status: Int) =
            TransportException(
                when (op) {
                    Op.CONNECT,
                    Op.DISCOVER,
                    Op.DESCRIPTOR_WRITE -> Kind.CONNECT_FAILED
                    else -> Kind.OPERATION_FAILED
                },
                status,
            )
    }
}
