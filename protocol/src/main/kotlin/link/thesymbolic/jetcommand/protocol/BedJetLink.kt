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

sealed interface LinkState {
    /** [gaveUp]: auto-reconnect ran out of attempts. */
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
 * and reconnects run there without locks, as the JS ran on one event loop. [now] (ms) only
 * rate-limits remainder reads.
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
        get() = session.attempt

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

    // When the link last went from up (or wanted) to down; the give-up clock runs from here.
    private var downSince: Long? = null

    private var session: Session = Session.Idle
        set(value) {
            field = value
            if (value == Session.Ready || value == Session.Idle) downSince = null
            _state.value = value.linkState
        }

    init {
        this.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            transport.notifications.collect { if (session.attached) receiveStatus(it) }
        }
        this.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            transport.state.collect { if (it is TransportState.Lost) handleDrop(it.reason) }
        }
    }

    suspend fun connect() {
        session = claimForCaller(session) ?: return
        val failure =
            connectOnce { s ->
                if (s == Session.Idle) throw BedJetException(ErrorCode.NOT_CONNECTED)
                claimForCaller(s)
            } ?: return
        if (failure.owned && config.retryFailedConnect && config.autoReconnect) {
            log(LogLevel.WARN, "Connect failed: ${failure.error.message}")
            scheduleReconnect(after = failure.attempt)
        }
        throw failure.error
    }

    /**
     * Cuts a pending reconnect's wait short and restarts the backoff, for when the cause of the
     * failures has likely gone (Bluetooth back on). No-op unless a reconnect is waiting or the link
     * gave up while still wanted.
     */
    fun retryNow() {
        if (!config.autoReconnect) return
        val s = session
        if (s !is Session.Waiting && (s as? Session.Down)?.gaveUp != true) return
        log(LogLevel.INFO, "Retrying now")
        s.job?.cancel()
        downSince = null
        runReconnect(attempt = 1, wait = 0)
    }

    /** Stays down: no reconnect follows. */
    suspend fun disconnect() {
        session.job?.cancel()
        session = Session.Idle
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

    /**
     * Where the link is, and whether anyone wants it: the one source of [state] and
     * [reconnectAttempt].
     */
    private sealed interface Session {
        val linkState: LinkState
        val attempt: Int
            get() = 0

        /** A reconnect waiting or connecting; cancelled when the session is taken over. */
        val job: Job?
            get() = null

        /** The transport is up and its notifications are ours. */
        val attached: Boolean
            get() = false

        /** Nobody wants the link: before [connect], after [disconnect]. */
        data object Idle : Session {
            override val linkState = LinkState.Disconnected()
        }

        /**
         * Wanted, but down with nothing scheduled: reconnects [gaveUp], a connect failed, or the
         * link dropped without auto-reconnect.
         */
        data class Down(val gaveUp: Boolean) : Session {
            override val linkState = LinkState.Disconnected(gaveUp)
        }

        data class Waiting(override val attempt: Int, override val job: Job) : Session {
            override val linkState = LinkState.Connecting(attempt)
        }

        /**
         * A connect running for the caller ([job] null) or a reconnect. Compared by identity: the
         * run that set it owns the session until something else replaces it, and after that leaves
         * the session alone.
         */
        class Connecting(
            override val attempt: Int,
            override val job: Job?,
            override val attached: Boolean = false,
        ) : Session {
            override val linkState = LinkState.Connecting(attempt)
        }

        data object Ready : Session {
            override val linkState = LinkState.Ready
            override val attached = true
        }
    }

    /** [owned]: the failed run still owned the session, so what follows is the run's to decide. */
    private class ConnectFailure(
        val error: BedJetException,
        val attempt: Int,
        val owned: Boolean,
    )

    /**
     * The caller's connect takes over a waiting or running reconnect, keeping its count, and joins
     * another caller's connect. Null: the link is up.
     */
    private fun claimForCaller(s: Session): Session.Connecting? =
        when (s) {
            Session.Ready -> null
            is Session.Connecting ->
                if (s.job == null) s
                else {
                    s.job.cancel()
                    Session.Connecting(s.attempt, job = null)
                }
            is Session.Waiting -> {
                s.job.cancel()
                Session.Connecting(s.attempt, job = null)
            }
            Session.Idle,
            is Session.Down -> Session.Connecting(0, job = null)
        }

    /**
     * Connects as the run [claim] makes of the session found under the lock, or does nothing when
     * [claim] returns null. Null result: up, or nothing to do.
     */
    private suspend fun connectOnce(claim: (Session) -> Session.Connecting?): ConnectFailure? =
        connectLock.withLock {
            var mine = claim(session) ?: return@withLock null
            session = mine
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
                    if (session !== mine) throw BedJetException(ErrorCode.NOT_CONNECTED)
                    missing.clear()
                    mine = Session.Connecting(mine.attempt, mine.job, attached = true)
                    session = mine
                    deviceName = "BedJet"
                    try {
                        Reply.parseText(serial { read(Characteristic.NAME) })
                            .takeIf { it.isNotEmpty() }
                            ?.let { deviceName = it }
                    } catch (e: BedJetException) {
                        log(LogLevel.WARN, "Could not read device name: ${e.message}")
                    }
                    if (session !== mine) throw BedJetException(ErrorCode.NOT_CONNECTED)
                    log(LogLevel.SUCCESS, "Connected to $deviceName")
                    session = Session.Ready
                }
                null
            } catch (e: BedJetException) {
                ConnectFailure(e, mine.attempt, owned)
            }
        }

    /** True when [mine] still owned the session, which is then [Session.Down]. */
    private suspend fun abortConnect(mine: Session.Connecting): Boolean {
        withContext(NonCancellable) {
            attempt(onError = { log(LogLevel.WARN, "Disconnect failed: ${it.message}") }) {
                transport.disconnect()
            }
        }
        // Under connectLock nobody else is attached, so the assembler is this run's either way.
        assembler = assembler.reset()
        if (session !== mine) return false
        session = Session.Down(gaveUp = false)
        return true
    }

    private fun handleDrop(reason: TransportException) {
        val s = session
        if (s != Session.Ready && s !is Session.Connecting) return
        log(LogLevel.WARN, "Connection lost: ${reason.message}")
        lastDropReason = reason.message
        assembler = assembler.reset()
        session = Session.Down(gaveUp = false)
        if (config.autoReconnect) scheduleReconnect(after = s.attempt)
    }

    private fun scheduleReconnect(after: Int) {
        val since = downSince ?: now().also { downSince = it }
        val downFor = now() - since
        when (val decision = reconnectDecision(after, config, downFor)) {
            ReconnectDecision.GiveUp -> {
                log(
                    LogLevel.ERROR,
                    "Gave up after $after reconnect attempts, down ${downFor / 1000} s",
                )
                session = Session.Down(gaveUp = true)
            }
            is ReconnectDecision.Retry -> {
                val max = config.maxReconnectAttempts ?: "∞"
                log(
                    LogLevel.WARN,
                    "Reconnect ${decision.attempt}/$max in ${decision.waitMs / 1000} s",
                )
                runReconnect(decision.attempt, decision.waitMs)
            }
        }
    }

    private fun runReconnect(attempt: Int, wait: Long) {
        lateinit var waiting: Session.Waiting
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                delay(wait)
                val failure =
                    connectOnce { s ->
                        if (s === waiting) Session.Connecting(attempt, waiting.job) else null
                    } ?: return@launch
                log(LogLevel.WARN, "Reconnect $attempt failed: ${failure.error.message}")
                if (failure.owned) scheduleReconnect(after = attempt)
            }
        waiting = Session.Waiting(attempt, job)
        session = waiting
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
            withTimeoutOrNull(config.timeoutMs) { block() }
                ?: throw BedJetException(ErrorCode.TIMEOUT)
        } catch (e: TransportException) {
            throw wrap(e, ErrorCode.BAD_RESPONSE)
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

    private fun receiveStatus(bytes: ByteArray) =
        act(assembler.notified(bytes, now(), config.remainderIntervalMs))

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
