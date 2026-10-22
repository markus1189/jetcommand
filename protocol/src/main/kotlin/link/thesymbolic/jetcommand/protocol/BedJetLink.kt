// Port of the web app's `BedJetWebLink`, minus the browser: device picking and remembering are the transport's job.
package link.thesymbolic.jetcommand.protocol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

sealed interface LinkState {
    /** [gaveUp]: auto-reconnect ran out of attempts. */
    data class Disconnected(val gaveUp: Boolean = false) : LinkState
    /** [reconnectAttempt] is 0 for a connect the caller asked for. */
    data class Connecting(val reconnectAttempt: Int = 0) : LinkState
    data object Ready : LinkState
}

/**
 * A parsed status. Bytes 27–29 ([Status.extra]) only arrive with the remainder of a split packet; when this
 * notification lacked them, [Status.extra] is the last known value and [extraFresh] is false.
 */
data class StatusUpdate(val status: Status, val extraFresh: Boolean)

enum class LogLevel { INFO, SUCCESS, WARN, ERROR }

/**
 * The BedJet protocol over a [BedJetTransport]: one GATT operation at a time, tagged queries with retries,
 * split status packets put back together, reconnects after a lost link.
 *
 * Confine [scope] to one thread (Main, or a `limitedParallelism(1)` dispatcher): status handling and reconnects
 * run there without locks, as the JS ran on one event loop. [now] (ms) only rate-limits remainder reads.
 */
class BedJetLink(
    private val transport: BedJetTransport,
    scope: CoroutineScope,
    val config: Config = Config(),
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val log: (LogLevel, String) -> Unit = { _, _ -> },
) {
    data class Config(
        val timeoutMs: Long = 5000,
        val connectTimeoutMs: Long = 20000,
        val queryAttempts: Int = 8,
        val queryRetryMs: Long = 400,
        val remainderIntervalMs: Long = 2000,
        /** Null: never give up while the connection is wanted. */
        val maxReconnectAttempts: Int? = 10,
        val reconnectBaseMs: Long = 3000,
        val reconnectMaxMs: Long = 60000,
        val autoReconnect: Boolean = true,
        /** A failed [connect] goes on retrying like a lost link instead of staying down. */
        val retryFailedConnect: Boolean = false,
    )

    private val scope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    private val _state = MutableStateFlow<LinkState>(LinkState.Disconnected())
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _statuses = MutableSharedFlow<StatusUpdate>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Every status as it is received or completed; [status] holds the latest. */
    val statuses: SharedFlow<StatusUpdate> = _statuses
    private val _status = MutableStateFlow<StatusUpdate?>(null)
    val status: StateFlow<StatusUpdate?> = _status.asStateFlow()

    /** [Characteristic.REPLY_ALT] or [Characteristic.REPLY] once a bio reply has matched. */
    var replySource: Characteristic? = null
        private set
    var deviceName: String? = null
        private set
    var reconnectAttempt = 0
        private set
    /** Why the link last dropped; set before the state leaves [LinkState.Ready]. */
    var lastDropReason: String? = null
        private set

    private val queue = Mutex()
    private val connectLock = Mutex()
    private var tag = 0
    private var attached = false
    private var wanted = false
    private var reconnectJob: Job? = null
    private val missing = mutableSetOf<Characteristic>()
    private var lastHead: ByteArray? = null
    private var lastExtra: StatusExtra? = null
    private var lastRemainderRead = Long.MIN_VALUE / 2
    private var remainderPending = false

    init {
        this.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            transport.notifications.collect { if (attached) receiveStatus(it) }
        }
        this.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            transport.state.collect { if (it is TransportState.Lost) handleDrop(it.reason) }
        }
    }

    suspend fun connect() {
        if (_state.value == LinkState.Ready) return
        wanted = true
        reconnectJob?.cancel()
        reconnectJob = null
        try {
            connectOnce()
        } catch (e: BedJetException) {
            if (wanted && config.retryFailedConnect && config.autoReconnect) {
                log(LogLevel.WARN, "Connect failed: ${e.message}")
                scheduleReconnect()
            }
            throw e
        }
    }

    /**
     * Cuts a pending reconnect's wait short and restarts the backoff, for when the cause of the failures has
     * likely gone (Bluetooth back on). No-op unless a reconnect is waiting or the link gave up while still wanted.
     */
    fun retryNow() {
        if (!wanted || !config.autoReconnect) return
        val waiting = reconnectJob?.isActive == true && !connecting
        val gaveUp = (_state.value as? LinkState.Disconnected)?.gaveUp == true
        if (!waiting && !gaveUp) return
        log(LogLevel.INFO, "Retrying now")
        reconnectJob?.cancel()
        reconnectAttempt = 1
        runReconnect(0)
    }

    /** Stays down: no reconnect follows. */
    suspend fun disconnect() {
        wanted = false
        reconnectAttempt = 0
        reconnectJob?.cancel()
        reconnectJob = null
        log(LogLevel.INFO, "Disconnecting")
        runCatching { transport.disconnect() }
        teardown()
    }

    suspend fun send(bytes: ByteArray) {
        requireReady()
        serial { write(bytes) }
    }

    /** The payload of the unit's answer to `41 id tag`, without the id and tag. */
    suspend fun query(id: Int): ByteArray {
        requireReady()
        return serial {
            tag = if (tag >= 255) 1 else tag + 1
            val tag = tag
            val request = Command.query(id, tag)
            // The reply characteristic keeps its previous contents until the unit has answered, so
            // only an echo of this request id and tag counts. Same tag on every retry, as the official app does.
            for (attempt in 1..config.queryAttempts) {
                write(request)
                for (ch in replyChars()) {
                    val reply = readReply(ch) ?: continue
                    if (reply.size >= 2 && reply.u(0) == id && reply.u(1) == tag) {
                        noteReplySource(ch)
                        return@serial reply.copyOfRange(2, reply.size)
                    }
                }
                if (attempt < config.queryAttempts) delay(config.queryRetryMs)
            }
            log(LogLevel.WARN, "No reply to query 0x${id.toString(16)} tag $tag after ${config.queryAttempts} attempts")
            throw BedJetException(ErrorCode.BAD_RESPONSE)
        }
    }

    /** Reads 2005 and 2006 as they are, without a query; null for a missing or unreadable characteristic. */
    suspend fun readReplies(): Map<Characteristic, ByteArray?> {
        requireReady()
        return serial {
            replyChars().associateWith { ch ->
                try {
                    readReply(ch)
                } catch (e: BedJetException) {
                    log(LogLevel.WARN, "Reading $ch failed: ${e.message}")
                    null
                }
            }
        }
    }

    // ---- connection ----

    private var connecting = false

    private suspend fun connectOnce() = connectLock.withLock {
        connecting = true
        try {
            connectLocked()
        } finally {
            connecting = false
        }
    }

    private suspend fun connectLocked() {
        if (_state.value == LinkState.Ready) return
        if (_state.value !is LinkState.Connecting) setState(LinkState.Connecting())
        try {
            withTimeoutOrNull(config.connectTimeoutMs) { transport.connect() }
                ?: throw BedJetException(ErrorCode.TIMEOUT)
            missing.clear()
            attached = true
            deviceName = "BedJet"
            try {
                Reply.parseText(serial { read(Characteristic.NAME) }).takeIf { it.isNotEmpty() }?.let { deviceName = it }
            } catch (e: BedJetException) {
                log(LogLevel.WARN, "Could not read device name: ${e.message}")
            }
            reconnectAttempt = 0
            log(LogLevel.SUCCESS, "Connected to $deviceName")
            setState(LinkState.Ready)
        } catch (e: Throwable) {
            withContext(NonCancellable) { runCatching { transport.disconnect() } }
            teardown()
            if (e is CancellationException || e is BedJetException) throw e
            throw wrap(e, ErrorCode.NOT_CONNECTED)
        }
    }

    private fun teardown() {
        attached = false
        lastHead = null
        lastExtra = null
        remainderPending = false
        setState(LinkState.Disconnected())
    }

    private fun handleDrop(reason: TransportException) {
        if (!attached && _state.value is LinkState.Disconnected) return
        log(LogLevel.WARN, "Connection lost: ${reason.message}")
        lastDropReason = reason.message
        teardown()
        if (!wanted || !config.autoReconnect) return
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        val max = config.maxReconnectAttempts
        if (max != null && reconnectAttempt >= max) {
            log(LogLevel.ERROR, "Gave up after $reconnectAttempt reconnect attempts")
            reconnectAttempt = 0
            setState(LinkState.Disconnected(gaveUp = true))
            return
        }
        reconnectAttempt += 1
        val wait = minOf(config.reconnectBaseMs shl (reconnectAttempt - 1).coerceAtMost(30), config.reconnectMaxMs)
        log(LogLevel.WARN, "Reconnect $reconnectAttempt/${max ?: "∞"} in ${wait / 1000} s")
        runReconnect(wait)
    }

    private fun runReconnect(wait: Long) {
        setState(LinkState.Connecting(reconnectAttempt))
        reconnectJob = scope.launch {
            delay(wait)
            if (!wanted) return@launch
            try {
                connectOnce()
            } catch (e: BedJetException) {
                log(LogLevel.WARN, "Reconnect $reconnectAttempt failed: ${e.message}")
                if (wanted) {
                    setState(LinkState.Connecting(reconnectAttempt))
                    scheduleReconnect()
                }
            }
        }
    }

    // ---- operations ----

    private fun requireReady() {
        if (_state.value != LinkState.Ready) throw BedJetException(ErrorCode.NOT_CONNECTED)
    }

    /** The unit handles one GATT operation at a time, so everything goes through one queue. */
    private suspend fun <T> serial(work: suspend () -> T): T = queue.withLock { work() }

    private suspend fun <T : Any> gatt(block: suspend () -> T): T = try {
        withTimeoutOrNull(config.timeoutMs) { block() } ?: throw BedJetException(ErrorCode.TIMEOUT)
    } catch (e: TransportException) {
        throw wrap(e, ErrorCode.BAD_RESPONSE)
    }

    private suspend fun write(bytes: ByteArray) = gatt { transport.write(Characteristic.COMMAND, bytes) }

    private suspend fun read(ch: Characteristic) = gatt { transport.read(ch) }

    /** Null when the unit lacks [ch]; remembered for the rest of the connection. */
    private suspend fun readReply(ch: Characteristic): ByteArray? {
        if (ch in missing) return null
        return try {
            read(ch)
        } catch (e: BedJetException) {
            if ((e.cause as? TransportException)?.kind != TransportException.Kind.MISSING_CHARACTERISTIC) throw e
            if (missing.isEmpty()) log(LogLevel.WARN, "No bio reply characteristic $ch")
            missing += ch
            null
        }
    }

    // 2006 first: on Chrome/Android a 2005 read stops at 22 bytes (ATT MTU 23, no long read), while 2006 returns
    // the whole reply (probe on firmware V1.20, 2026-10-21). 2005 stays as fallback for units without 2006.
    private fun replyChars() = listOf(Characteristic.REPLY_ALT, Characteristic.REPLY)

    private fun noteReplySource(ch: Characteristic) {
        if (replySource == ch) return
        log(LogLevel.INFO, "Bio replies come from $ch${replySource?.let { " (was $it)" } ?: ""}")
        replySource = ch
    }

    // ---- status ----

    private fun receiveStatus(bytes: ByteArray) {
        if (Reply.isStatusStart(bytes)) receiveHead(bytes)
        else if (remainderPending) mergeRemainder(bytes)
    }

    private fun receiveHead(bytes: ByteArray) {
        val status = Reply.parseStatus(bytes) ?: return log(LogLevel.WARN, "Unparseable status: ${bytes.toHex()}")
        val update = if (status.extra != null) {
            lastExtra = status.extra
            lastHead = null
            StatusUpdate(status, extraFresh = true)
        } else {
            // Bytes 27–29 lag behind: show the last known ones and fetch the remainder now and then.
            lastHead = bytes
            val t = now()
            if (bytes.u(0) == 1 && !remainderPending && t - lastRemainderRead >= config.remainderIntervalMs) {
                lastRemainderRead = t
                readRemainder()
            }
            StatusUpdate(status.copy(extra = lastExtra), extraFresh = false)
        }
        emit(update)
    }

    private fun readRemainder() {
        remainderPending = true
        scope.launch {
            try {
                // Android may also notify the read value; whichever path arrives first merges.
                val bytes = serial { read(Characteristic.STATUS) }
                if (remainderPending && !Reply.isStatusStart(bytes)) mergeRemainder(bytes)
            } catch (e: BedJetException) {
                log(LogLevel.WARN, "Status remainder read failed: ${e.message}")
            } finally {
                remainderPending = false
            }
        }
    }

    private fun mergeRemainder(rest: ByteArray) {
        remainderPending = false
        val head = lastHead ?: return
        val status = Reply.parseStatus(head + rest)
        if (status?.extra == null) return log(LogLevel.WARN, "Status remainder didn't complete the packet: ${rest.toHex()}")
        lastExtra = status.extra
        emit(StatusUpdate(status, extraFresh = true))
    }

    private fun emit(update: StatusUpdate) {
        _status.value = update
        _statuses.tryEmit(update)
    }

    private fun setState(state: LinkState) {
        _state.value = state
    }

    private fun wrap(e: Throwable, fallback: ErrorCode): BedJetException {
        if (e is BedJetException) return e
        val code = when ((e as? TransportException)?.kind) {
            TransportException.Kind.NOT_CONNECTED, TransportException.Kind.DISCONNECTED -> ErrorCode.NOT_CONNECTED
            TransportException.Kind.TIMEOUT -> ErrorCode.TIMEOUT
            else -> fallback
        }
        return BedJetException(code, "${code.text} (${e.message})", e)
    }
}
