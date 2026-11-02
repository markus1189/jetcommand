// Port of the web app's `BedJetWebLink`, minus the browser: device picking and remembering are the
// transport's job.
package link.thesymbolic.jetcommand.protocol

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
import link.thesymbolic.jetcommand.protocol.LinkSession.Phase

sealed interface LinkState {
    /** [gaveUp]: auto-reconnect ran out of attempts or of [BedJetLink.Config.giveUpAfterDownMs]. */
    data class Disconnected(val gaveUp: Boolean = false) : LinkState

    /** [reconnectAttempt] is 0 for a connect the caller asked for. */
    data class Connecting(val reconnectAttempt: Int = 0) : LinkState

    data object Ready : LinkState
}

/**
 * A parsed status. Bytes 27–29 ([Status.extra]) only arrive with the remainder of a split packet;
 * when this notification lacked them, [Status.extra] is the last known value and [extraFresh] is
 * false.
 */
data class StatusUpdate(val status: Status, val extraFresh: Boolean)

enum class LogLevel {
    INFO,
    SUCCESS,
    WARN,
    ERROR,
}

/**
 * The BedJet protocol over a [BedJetTransport]: one GATT operation at a time, tagged queries with
 * retries, split status packets put back together, reconnects after a lost link.
 *
 * Confine [scope] to one thread (Main, or a `limitedParallelism(1)` dispatcher): status handling
 * and reconnects run there without locks, as the JS ran on one event loop; a second thread fails
 * fast. [now] (ms) only rate-limits remainder reads.
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
        /** Null: no limit on how long the link may stay down while reconnecting. */
        val giveUpAfterDownMs: Long? = null,
        val reconnectBaseMs: Long = 3000,
        val reconnectMaxMs: Long = 60000,
        val autoReconnect: Boolean = true,
        /** A failed [connect] goes on retrying like a lost link instead of staying down. */
        val retryFailedConnect: Boolean = false,
    )

    private val scope =
        CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    private val _state = MutableStateFlow<LinkState>(LinkState.Disconnected())
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _statuses =
        MutableSharedFlow<StatusUpdate>(
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    /** Every status as it is received or completed; [status] holds the latest. */
    val statuses: SharedFlow<StatusUpdate> = _statuses
    private val _status = MutableStateFlow<StatusUpdate?>(null)
    val status: StateFlow<StatusUpdate?> = _status.asStateFlow()

    /** [Characteristic.REPLY_ALT] or [Characteristic.REPLY] once a bio reply has matched. */
    var replySource: Characteristic? = null
        private set

    var deviceName: String? = null
        private set

    /** The reconnect waiting or running, 0 when none is; a [connect] taking over keeps it. */
    val reconnectAttempt: Int
        get() = session.phase.attempt

    /** Why the link last dropped; set before the state leaves [LinkState.Ready]. */
    var lastDropReason: String? = null
        private set

    private val queue = Mutex()
    private val connectLock = Mutex()
    // Kept across reconnects: a reply matches on id and tag, so only consecutive tags must differ.
    private var tag = 0
    // Per connection, but readReply grows it mid-operation; in the session it would race with
    // session changes made while that operation suspends.
    private val missing = mutableSetOf<Characteristic>()
    private var assembler = StatusAssembler()

    // The waiting or running reconnect's; LinkEffect.CancelReconnect only comes while there is one.
    private var reconnectJob: Job? = null

    // Taken from the first use, not the constructor: the scope's dispatcher may not have run yet.
    private var thread: Thread? = null

    private var session = LinkSession()
        set(value) {
            confined()
            field = value
            _state.value = value.phase.linkState
        }

    init {
        this.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            transport.notifications.collect { if (session.phase.attached) receiveStatus(it) }
        }
        this.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            transport.state.collect { if (it is TransportState.Lost) handleDrop(it.reason) }
        }
    }

    suspend fun connect() {
        dispatch(LinkEvent.Connect)
        if (session.phase !is Phase.Connecting) return
        val failure =
            connectOnce {
                if (session.phase == Phase.Idle) throw BedJetException(ErrorCode.NOT_CONNECTED)
                dispatch(LinkEvent.Connect)
                session.phase as? Phase.Connecting
            } ?: return
        dispatch(LinkEvent.ConnectFailed(failure.attempt, failure.owned, failure.error.message))
        throw failure.error
    }

    /**
     * Cuts a pending reconnect's wait short and restarts the backoff, for when the cause of the
     * failures has likely gone (Bluetooth back on). No-op unless a reconnect is waiting or the link
     * gave up while still wanted.
     */
    fun retryNow() {
        dispatch(LinkEvent.RetryNow)
    }

    /** Stays down: no reconnect follows. */
    suspend fun disconnect() {
        dispatch(LinkEvent.Disconnect)
        log(LogLevel.INFO, "Disconnecting")
        attempt(onError = { log(LogLevel.WARN, "Disconnect failed: ${it.message}") }) {
            transport.disconnect()
        }
        assembler = assembler.reset()
    }

    suspend fun send(bytes: ByteArray) {
        requireReady()
        serial { write(bytes) }
    }

    /** Sends [frames] back to back: no other operation reaches the unit between them. */
    suspend fun sendAll(frames: List<ByteArray>) {
        requireReady()
        serial {
            for (frame in frames) {
                requireReady()
                write(frame)
            }
        }
    }

    /** The payload of the unit's answer to `41 id tag`, without the id and tag. */
    suspend fun query(id: Int): ByteArray {
        requireReady()
        return serial {
            tag = if (tag >= 255) 1 else tag + 1
            val tag = tag
            val request = Command.query(id, tag)
            // The reply characteristic keeps its previous contents until the unit has answered, so
            // only an echo of this request id and tag counts. Same tag on every retry, as the
            // official app does.
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
            log(
                LogLevel.WARN,
                "No reply to query 0x${id.toString(16)} tag $tag after ${config.queryAttempts} attempts",
            )
            throw BedJetException(ErrorCode.BAD_RESPONSE)
        }
    }

    /**
     * Reads 2005 and 2006 as they are, without a query; null for a missing or unreadable
     * characteristic.
     */
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

    /** [owned]: the failed run still owned the session, so what follows is the run's to decide. */
    private class ConnectFailure(
        val error: BedJetException,
        val attempt: Int,
        val owned: Boolean,
    )

    /** Runs [event] through [linkStep]: [LinkEffect.Before] effects, commit, then the rest. */
    private fun dispatch(event: LinkEvent) {
        confined()
        val s = session
        val t = linkStep(s, event, now(), config)
        t.effects.filterIsInstance<LinkEffect.Before>().forEach(::run)
        // Committing over a nested dispatch would rewind it, nextRun included.
        check(session === s) { "Session changed while dispatching $event" }
        session = t.next
        t.effects.filter { it !is LinkEffect.Before }.forEach(::run)
    }

    private fun run(effect: LinkEffect) {
        when (effect) {
            is LinkEffect.Log -> log(effect.level, effect.message)
            is LinkEffect.RecordDropReason -> lastDropReason = effect.reason
            LinkEffect.ResetAssembler -> assembler = assembler.reset()
            LinkEffect.CancelReconnect -> reconnectJob?.cancel()
            is LinkEffect.StartReconnect -> startReconnect(effect.run, effect.waitMs)
            is LinkEffect.FollowUp -> dispatch(effect.event)
        }
    }

    /**
     * Connects as the run [claim] dispatches and returns, or does nothing when it returns null.
     * Null result: up, or nothing to do.
     */
    private suspend fun connectOnce(claim: () -> Phase.Connecting?): ConnectFailure? =
        connectLock.withLock {
            var mine = claim() ?: return@withLock null
            var owned = true
            try {
                cleanupOnFailure({ owned = abortConnect(mine) }) {
                    try {
                        withTimeoutOrNull(config.connectTimeoutMs) { transport.connect() }
                            ?: throw BedJetException(ErrorCode.TIMEOUT)
                    } catch (e: TransportException) {
                        throw wrap(e, ErrorCode.NOT_CONNECTED)
                    }
                    // Taken over (disconnect, drop) while the transport connected.
                    if (session.phase != mine) throw BedJetException(ErrorCode.NOT_CONNECTED)
                    missing.clear()
                    mine = mine.copy(attached = true)
                    session = session.enter(mine)
                    deviceName = "BedJet"
                    try {
                        Reply.parseText(serial { read(Characteristic.NAME) })
                            .takeIf { it.isNotEmpty() }
                            ?.let { deviceName = it }
                    } catch (e: BedJetException) {
                        log(LogLevel.WARN, "Could not read device name: ${e.message}")
                    }
                    if (session.phase != mine) throw BedJetException(ErrorCode.NOT_CONNECTED)
                    log(LogLevel.SUCCESS, "Connected to $deviceName")
                    session = session.enter(Phase.Ready)
                }
                null
            } catch (e: BedJetException) {
                ConnectFailure(e, mine.attempt, owned)
            }
        }

    /** True when [mine] still owned the session, which is then [Phase.Down]. */
    private suspend fun abortConnect(mine: Phase.Connecting): Boolean {
        withContext(NonCancellable) {
            attempt(onError = { log(LogLevel.WARN, "Disconnect failed: ${it.message}") }) {
                transport.disconnect()
            }
        }
        // Under connectLock nobody else is attached, so the assembler is this run's either way.
        assembler = assembler.reset()
        val owned = session.phase == mine
        dispatch(LinkEvent.ConnectAborted(mine))
        return owned
    }

    private fun handleDrop(reason: TransportException) = dispatch(LinkEvent.Dropped(reason.message))

    private fun startReconnect(run: Int, wait: Long) {
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                delay(wait)
                val failure =
                    connectOnce {
                        dispatch(LinkEvent.ReconnectDue(run))
                        (session.phase as? Phase.Connecting)?.takeIf { it.run == run }
                    } ?: return@launch
                dispatch(
                    LinkEvent.ReconnectFailed(failure.attempt, failure.owned, failure.error.message)
                )
            }
        reconnectJob = job
        job.start()
    }

    // ---- operations ----

    private fun requireReady() {
        if (_state.value != LinkState.Ready) throw BedJetException(ErrorCode.NOT_CONNECTED)
    }

    /** The unit handles one GATT operation at a time, so everything goes through one queue. */
    private suspend fun <T> serial(work: suspend () -> T): T = queue.withLock { work() }

    private suspend fun <T : Any> gatt(block: suspend () -> T): T =
        try {
            withTimeoutOrNull(config.timeoutMs) { block() } ?: throw timedOut()
        } catch (e: TransportException) {
            throw wrap(e, ErrorCode.BAD_RESPONSE)
        }

    /** See [BedJetTransport]: after a cancelled operation the connection can't be trusted. */
    private suspend fun timedOut(): BedJetException {
        if (session.phase.attached) {
            withContext(NonCancellable) {
                attempt(onError = { log(LogLevel.WARN, "Disconnect failed: ${it.message}") }) {
                    transport.disconnect()
                }
            }
            handleDrop(
                TransportException(TransportException.Kind.TIMEOUT, message = "operation timed out")
            )
        }
        return BedJetException(ErrorCode.TIMEOUT)
    }

    private suspend fun write(bytes: ByteArray) = gatt {
        transport.write(Characteristic.COMMAND, bytes)
    }

    private suspend fun read(ch: Characteristic) = gatt { transport.read(ch) }

    /** Null when the unit lacks [ch]; remembered for the rest of the connection. */
    private suspend fun readReply(ch: Characteristic): ByteArray? {
        if (ch in missing) return null
        return try {
            read(ch)
        } catch (e: BedJetException) {
            if (
                (e.cause as? TransportException)?.kind !=
                    TransportException.Kind.MISSING_CHARACTERISTIC
            )
                throw e
            if (missing.isEmpty()) log(LogLevel.WARN, "No bio reply characteristic $ch")
            missing += ch
            null
        }
    }

    // 2006 first: on Chrome/Android a 2005 read stops at 22 bytes (ATT MTU 23, no long read), while
    // 2006 returns
    // the whole reply (probe on firmware V1.20, 2026-10-21). 2005 stays as fallback for units
    // without 2006.
    private fun replyChars() = listOf(Characteristic.REPLY_ALT, Characteristic.REPLY)

    private fun noteReplySource(ch: Characteristic) {
        if (replySource == ch) return
        log(LogLevel.INFO, "Bio replies come from $ch${replySource?.let { " (was $it)" } ?: ""}")
        replySource = ch
    }

    // ---- status ----

    private fun receiveStatus(bytes: ByteArray) {
        confined()
        act(assembler.notified(bytes, now(), config.remainderIntervalMs))
    }

    private fun confined() {
        val t = Thread.currentThread()
        val owner = thread ?: t.also { thread = it }
        check(owner === t) { "BedJetLink used on ${t.name}, confined to ${owner.name}" }
    }

    private fun act(step: Assembled) {
        assembler = step.next
        if (step.readRemainder) readRemainder()
        step.warning?.let { log(LogLevel.WARN, it) }
        step.update?.let(::emit)
    }

    private fun readRemainder() {
        scope.launch {
            try {
                act(assembler.remainderRead(serial { read(Characteristic.STATUS) }))
            } catch (e: BedJetException) {
                log(LogLevel.WARN, "Status remainder read failed: ${e.message}")
            } finally {
                assembler = assembler.remainderSettled()
            }
        }
    }

    private fun emit(update: StatusUpdate) {
        _status.value = update
        _statuses.tryEmit(update)
    }

    private fun wrap(e: Throwable, fallback: ErrorCode): BedJetException {
        if (e is BedJetException) return e
        val code =
            when ((e as? TransportException)?.kind) {
                TransportException.Kind.NOT_CONNECTED,
                TransportException.Kind.DISCONNECTED -> ErrorCode.NOT_CONNECTED
                TransportException.Kind.TIMEOUT -> ErrorCode.TIMEOUT
                else -> fallback
            }
        return BedJetException(code, "${code.text} (${e.message})", e)
    }
}
