package link.thesymbolic.jetcommand.protocol

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class Characteristic(val uuid: String) {
    STATUS(Uuids.STATUS),
    NAME(Uuids.NAME),
    COMMAND(Uuids.COMMAND),
    REPLY(Uuids.REPLY),
    REPLY_ALT(Uuids.REPLY_ALT),
}

sealed interface TransportState {
    data object Disconnected : TransportState

    data object Connecting : TransportState

    /** Services discovered and status notifications enabled. [mtu] is the negotiated ATT MTU. */
    data class Connected(val mtu: Int) : TransportState

    /** The link went away without [BedJetTransport.disconnect] being called. */
    data class Lost(val reason: TransportException) : TransportState
}

/**
 * A GATT failure. [gattStatus] is Android's raw status (133 = GATT_ERROR, the usual flaky-connect
 * one) when there is one; mapping to user-facing [ErrorCode]s is the link's job.
 */
class TransportException(
    val kind: Kind,
    val gattStatus: Int? = null,
    message: String = kind.name + (gattStatus?.let { " (status $it)" } ?: ""),
) : Exception(message) {
    enum class Kind {
        NOT_CONNECTED,
        CONNECT_FAILED,
        MISSING_CHARACTERISTIC,
        OPERATION_FAILED,
        TIMEOUT,
        DISCONNECTED,
    }
}

/**
 * GATT primitives against one BedJet, nothing protocol-specific. Implementations may assume one
 * operation at a time: Android GATT drops overlapping operations, so serializing is the caller's
 * (the link's) job.
 */
interface BedJetTransport {
    val state: StateFlow<TransportState>

    /**
     * Values notified on [Characteristic.STATUS], as they arrive (one notification may be a partial
     * packet).
     */
    val notifications: SharedFlow<ByteArray>

    /**
     * Connects, discovers services and enables status notifications. Throws [TransportException].
     */
    suspend fun connect()

    suspend fun disconnect()

    /**
     * Write with response. Only [Characteristic.COMMAND] and [Characteristic.NAME] are writable.
     */
    suspend fun write(characteristic: Characteristic, bytes: ByteArray)

    /**
     * Throws [TransportException] with [TransportException.Kind.MISSING_CHARACTERISTIC] when the
     * unit lacks it.
     */
    suspend fun read(characteristic: Characteristic): ByteArray
}
