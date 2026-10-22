package link.thesymbolic.jetcommand.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlin.random.Random

/**
 * A [BedJetTransport] over a [BedJetSim] that behaves like the real unit on the wire, for demo mode and tests.
 *
 * Device time is [scope]'s clock: the sim ticks once a second and, while connected, notifies its status. It keeps
 * running while disconnected, as a BedJet does when the phone leaves. Under `runTest` pass `backgroundScope`.
 */
class SimTransport(
    val sim: BedJetSim,
    private val scope: CoroutineScope,
    val mtu: Int = 23,
    private val hasReplyAlt: Boolean = true,
) : BedJetTransport {
    sealed interface Op {
        data object Connect : Op
        data class Write(val characteristic: Characteristic, val bytes: ByteArray) : Op
        data class Read(val characteristic: Characteristic) : Op
    }

    sealed interface Fault {
        /** On [Op.Connect] a [TransportException.Kind.CONNECT_FAILED], else an operation failure. */
        data class Fail(val gattStatus: Int = 133) : Fault
        /** Never completes, until the link drops. */
        data object Hang : Fault
        /** The link drops during the operation. */
        data object LoseLink : Fault
        data class Delay(val ms: Long) : Fault
    }

    /** The real unit's reply characteristics keep their previous contents for a while after a query. */
    data class ReplyLatency(val staleReads: Int = 0, val delayMs: Long = 0)

    /** Seeded random faults on top of the scripted ones. */
    data class Chaos(val seed: Long, val failRate: Double = 0.0, val dropRate: Double = 0.0)

    private class Rule(val fault: Fault, var times: Int, val where: (Op) -> Boolean)

    private val _state = MutableStateFlow<TransportState>(TransportState.Disconnected)
    override val state: StateFlow<TransportState> = _state.asStateFlow()
    private val _notifications = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val notifications: SharedFlow<ByteArray> = _notifications

    var latencyMs = 0L
    var replyLatency = ReplyLatency()
    var dropNotifications = 0
    var chaos: Chaos? = null
        set(value) {
            field = value
            random = value?.let { Random(it.seed) }
        }
    private var random: Random? = null
    private val rules = mutableListOf<Rule>()

    private val _ops = mutableListOf<Op>()
    /** Every operation attempted, in order. */
    val ops: List<Op> get() = _ops.toList()
    val writes get() = ops.filterIsInstance<Op.Write>().map { it.bytes.toHex() }

    private var connection: Job? = null
    private var reply = byteArrayOf(0, 0)
    private class PendingReply(val bytes: ByteArray, var staleReads: Int, var timeReady: Boolean)
    private var pendingReply: PendingReply? = null
    private val headSize get() = minOf(30, mtu - 3)

    init {
        scope.launch {
            while (true) {
                delay(1000)
                sim.tick()
                notifyStatus()
            }
        }
    }

    /** Makes the next [times] operations matching [where] hit [fault]. */
    fun inject(fault: Fault, times: Int = 1, where: (Op) -> Boolean = { true }) {
        rules += Rule(fault, times, where)
    }

    /** The unit walks out of range: state goes [TransportState.Lost], running operations fail. */
    fun loseLink() {
        val conn = connection ?: return
        connection = null
        _state.value = TransportState.Lost(TransportException(TransportException.Kind.DISCONNECTED))
        conn.cancel()
    }

    /** Notifies the current status now instead of waiting for the next second. */
    fun notifyStatus() {
        if (connection == null) return
        if (dropNotifications > 0) {
            dropNotifications--
            return
        }
        if (random?.let { it.nextDouble() < chaos!!.dropRate } == true) return
        val packet = sim.packet()
        if (headSize < packet.size) {
            val head = packet.copyOf(headSize)
            head[0] = 1
            _notifications.tryEmit(head)
        } else _notifications.tryEmit(packet)
    }

    override suspend fun connect() {
        if (connection != null) return
        _state.value = TransportState.Connecting
        try {
            perform(Op.Connect, null) {}
        } catch (e: Throwable) {
            _state.value = TransportState.Disconnected
            throw e
        }
        connection = Job()
        _state.value = TransportState.Connected(mtu)
    }

    override suspend fun disconnect() {
        val conn = connection
        connection = null
        _state.value = TransportState.Disconnected
        conn?.cancel()
    }

    override suspend fun write(characteristic: Characteristic, bytes: ByteArray) {
        perform(Op.Write(characteristic, bytes.copyOf()), connected()) {
            when (characteristic) {
                Characteristic.COMMAND -> command(bytes)
                Characteristic.NAME -> sim.deviceName = Reply.parseText(bytes)
                else -> throw TransportException(TransportException.Kind.OPERATION_FAILED, gattStatus = 3)
            }
        }
    }

    override suspend fun read(characteristic: Characteristic): ByteArray =
        perform(Op.Read(characteristic), connected()) {
            when (characteristic) {
                Characteristic.STATUS -> sim.packet().let { if (headSize < it.size) it.copyOfRange(headSize, it.size) else it }
                Characteristic.NAME -> sim.deviceName.toByteArray(Charsets.ISO_8859_1)
                Characteristic.COMMAND -> throw TransportException(TransportException.Kind.OPERATION_FAILED, gattStatus = 2)
                Characteristic.REPLY -> currentReply().let { it.copyOf(minOf(it.size, mtu - 1)) }
                Characteristic.REPLY_ALT ->
                    if (hasReplyAlt) currentReply()
                    else throw TransportException(TransportException.Kind.MISSING_CHARACTERISTIC)
            }
        }

    private fun connected() = connection ?: throw TransportException(TransportException.Kind.NOT_CONNECTED)

    private fun command(bytes: ByteArray) {
        if (bytes.size >= 3 && bytes.u(0) == 0x41) {
            val answer = try {
                byteArrayOf(bytes[1], bytes[2]) + sim.query(bytes.u(1))
            } catch (_: BedJetException) {
                return // the real unit doesn't answer unknown ids; the old reply stays
            }
            // A retry of the request still waiting for its answer doesn't restart the wait.
            if (pendingReply?.bytes?.let { it[0] == bytes[1] && it[1] == bytes[2] } == true) return
            val pending = PendingReply(answer, replyLatency.staleReads, timeReady = replyLatency.delayMs <= 0)
            pendingReply = pending
            if (!pending.timeReady) scope.launch {
                delay(replyLatency.delayMs)
                pending.timeReady = true
            }
            return
        }
        try {
            sim.send(bytes)
        } catch (_: BedJetException) {
            // A short frame is acknowledged and ignored, as on the unit.
        }
    }

    private fun currentReply(): ByteArray {
        pendingReply?.let { p ->
            if (p.staleReads == 0 && p.timeReady) {
                reply = p.bytes
                pendingReply = null
            } else if (p.staleReads > 0) p.staleReads--
        }
        return reply.copyOf()
    }

    private fun faultFor(op: Op): Fault? {
        rules.firstOrNull { it.times > 0 && it.where(op) }?.let { rule ->
            rule.times--
            if (rule.times == 0) rules.remove(rule)
            return rule.fault
        }
        val c = chaos ?: return null
        return if (random!!.nextDouble() < c.failRate) Fault.Fail() else null
    }

    private suspend fun <T> perform(op: Op, conn: Job?, body: () -> T): T {
        _ops += op
        val fault = faultFor(op)
        val kind = if (op == Op.Connect) TransportException.Kind.CONNECT_FAILED else TransportException.Kind.OPERATION_FAILED
        return coroutineScope {
            val work = async {
                delay(latencyMs)
                when (fault) {
                    is Fault.Fail -> throw TransportException(kind, fault.gattStatus)
                    Fault.Hang -> awaitCancellation()
                    Fault.LoseLink -> {
                        loseLink()
                        throw TransportException(TransportException.Kind.DISCONNECTED)
                    }
                    is Fault.Delay -> delay(fault.ms)
                    null -> Unit
                }
                if (conn != null && connection !== conn) throw TransportException(TransportException.Kind.DISCONNECTED)
                body()
            }
            if (conn == null) work.await()
            else select {
                work.onAwait { it }
                conn.onJoin {
                    work.cancel()
                    throw TransportException(TransportException.Kind.DISCONNECTED)
                }
            }
        }
    }
}
