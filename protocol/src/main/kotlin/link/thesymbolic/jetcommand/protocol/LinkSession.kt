package link.thesymbolic.jetcommand.protocol

/**
 * Where the link is and whether anyone wants it, decided by [linkStep]; [BedJetLink] holds one and
 * runs the effects. [downSince] (ms) starts the give-up clock; [nextRun] numbers the connect runs.
 */
data class LinkSession(
    val phase: Phase = Phase.Idle,
    val downSince: Long? = null,
    val nextRun: Int = 1,
) {
    sealed interface Phase {
        val linkState: LinkState
        val attempt: Int
            get() = 0

        /** The transport is up and its notifications are ours. */
        val attached: Boolean
            get() = false

        /** Nobody wants the link: before connect, after disconnect. */
        data object Idle : Phase {
            override val linkState = LinkState.Disconnected()
        }

        /**
         * Wanted, but down with nothing scheduled: reconnects [gaveUp], a connect failed, or the
         * link dropped without auto-reconnect.
         */
        data class Down(val gaveUp: Boolean) : Phase {
            override val linkState = LinkState.Disconnected(gaveUp)
        }

        /** Reconnect [attempt] waiting to start as [run]. */
        data class Waiting(override val attempt: Int, val run: Int) : Phase {
            override val linkState = LinkState.Connecting(attempt)
        }

        /**
         * Connect [run], for the caller or a [reconnect]. The run owns the session while the phase
         * still equals the one it set: run numbers are never reused.
         */
        data class Connecting(
            override val attempt: Int,
            val run: Int,
            val reconnect: Boolean,
            override val attached: Boolean = false,
        ) : Phase {
            override val linkState = LinkState.Connecting(attempt)
        }

        data object Ready : Phase {
            override val linkState = LinkState.Ready
            override val attached = true
        }
    }

    /** Enters [p]; up or unwanted stops the give-up clock. */
    fun enter(p: Phase): LinkSession =
        copy(phase = p, downSince = if (p == Phase.Ready || p == Phase.Idle) null else downSince)
}

sealed interface LinkEvent {
    /** The caller's connect. */
    data object Connect : LinkEvent

    /** Waiting run [run]'s delay ran out. */
    data class ReconnectDue(val run: Int) : LinkEvent

    /** Run [mine] failed and disconnected the transport. */
    data class ConnectAborted(val mine: LinkSession.Phase.Connecting) : LinkEvent

    /** The caller's connect failed at [attempt]; [owned]: its run still owned the session. */
    data class ConnectFailed(val attempt: Int, val owned: Boolean, val message: String?) : LinkEvent

    data class ReconnectFailed(val attempt: Int, val owned: Boolean, val message: String?) :
        LinkEvent

    /** The transport was lost, or an operation timed out. */
    data class Dropped(val reason: String?) : LinkEvent

    /** Decide what follows reconnect attempt [after] (0: up before, or the caller's connect). */
    data class Schedule(val after: Int) : LinkEvent

    data object RetryNow : LinkEvent

    data object Disconnect : LinkEvent
}

/**
 * [Before] effects run before the shell commits the new session, the others after. [FollowUp] feeds
 * an event back after the commit, so its states are observed in order.
 */
sealed interface LinkEffect {
    sealed interface Before : LinkEffect

    data class Log(val level: LogLevel, val message: String) : Before

    data class RecordDropReason(val reason: String?) : Before

    data object ResetAssembler : Before

    /** Cancel the job of the waiting or running reconnect. */
    data object CancelReconnect : Before

    /** Launch run [run]: wait [waitMs], then [LinkEvent.ReconnectDue]. */
    data class StartReconnect(val run: Int, val waitMs: Long) : LinkEffect

    data class FollowUp(val event: LinkEvent) : LinkEffect
}

data class LinkTransition(val next: LinkSession, val effects: List<LinkEffect> = emptyList())

fun linkStep(
    s: LinkSession,
    event: LinkEvent,
    now: Long,
    config: BedJetLink.Config,
): LinkTransition =
    when (event) {
        LinkEvent.Connect -> connect(s)
        is LinkEvent.ReconnectDue -> {
            val p = s.phase
            if (p is LinkSession.Phase.Waiting && p.run == event.run)
                LinkTransition(s.enter(LinkSession.Phase.Connecting(p.attempt, p.run, true)))
            else LinkTransition(s)
        }
        is LinkEvent.ConnectAborted ->
            if (s.phase == event.mine)
                LinkTransition(s.enter(LinkSession.Phase.Down(gaveUp = false)))
            else LinkTransition(s)
        is LinkEvent.ConnectFailed -> connectFailed(s, event, config)
        is LinkEvent.ReconnectFailed ->
            LinkTransition(
                s,
                listOfNotNull(
                    LinkEffect.Log(
                        LogLevel.WARN,
                        "Reconnect ${event.attempt} failed: ${event.message}",
                    ),
                    LinkEffect.FollowUp(LinkEvent.Schedule(event.attempt)).takeIf { event.owned },
                ),
            )
        is LinkEvent.Dropped -> dropped(s, event.reason, config)
        is LinkEvent.Schedule -> schedule(s, event.after, now, config)
        LinkEvent.RetryNow -> retryNow(s, config)
        LinkEvent.Disconnect ->
            LinkTransition(
                s.enter(LinkSession.Phase.Idle),
                listOfNotNull(LinkEffect.CancelReconnect.takeIf { s.phase.hasReconnect }),
            )
    }

private fun connect(s: LinkSession): LinkTransition =
    when (val p = s.phase) {
        LinkSession.Phase.Ready -> LinkTransition(s)
        is LinkSession.Phase.Connecting ->
            if (!p.reconnect) LinkTransition(s) else takeOver(s, p.attempt, cancel = true)
        is LinkSession.Phase.Waiting -> takeOver(s, p.attempt, cancel = true)
        LinkSession.Phase.Idle,
        is LinkSession.Phase.Down -> takeOver(s, 0, cancel = false)
    }

private fun connectFailed(
    s: LinkSession,
    event: LinkEvent.ConnectFailed,
    config: BedJetLink.Config,
): LinkTransition =
    if (event.owned && config.retryFailedConnect && config.autoReconnect)
        LinkTransition(
            s,
            listOf(
                LinkEffect.Log(LogLevel.WARN, "Connect failed: ${event.message}"),
                LinkEffect.FollowUp(LinkEvent.Schedule(event.attempt)),
            ),
        )
    else LinkTransition(s)

private fun dropped(s: LinkSession, reason: String?, config: BedJetLink.Config): LinkTransition {
    val p = s.phase
    if (p != LinkSession.Phase.Ready && p !is LinkSession.Phase.Connecting) return LinkTransition(s)
    return LinkTransition(
        s.enter(LinkSession.Phase.Down(gaveUp = false)),
        listOfNotNull(
            LinkEffect.Log(LogLevel.WARN, "Connection lost: $reason"),
            LinkEffect.RecordDropReason(reason),
            LinkEffect.ResetAssembler,
            LinkEffect.FollowUp(LinkEvent.Schedule(p.attempt)).takeIf { config.autoReconnect },
        ),
    )
}

private fun retryNow(s: LinkSession, config: BedJetLink.Config): LinkTransition {
    val p = s.phase
    val gaveUp = (p as? LinkSession.Phase.Down)?.gaveUp == true
    if (!config.autoReconnect || (p !is LinkSession.Phase.Waiting && !gaveUp))
        return LinkTransition(s)
    val waiting = waitFor(s.copy(downSince = null), attempt = 1, waitMs = 0)
    return waiting.copy(
        effects =
            listOfNotNull(
                LinkEffect.Log(LogLevel.INFO, "Retrying now"),
                LinkEffect.CancelReconnect.takeIf { p is LinkSession.Phase.Waiting },
            ) + waiting.effects
    )
}

private val LinkSession.Phase.hasReconnect: Boolean
    get() =
        this is LinkSession.Phase.Waiting ||
            (this is LinkSession.Phase.Connecting && this.reconnect)

/** The caller's connect takes over at [attempt], keeping a reconnect's count. */
private fun takeOver(s: LinkSession, attempt: Int, cancel: Boolean) =
    LinkTransition(
        s.enter(LinkSession.Phase.Connecting(attempt, s.nextRun, reconnect = false))
            .copy(nextRun = s.nextRun + 1),
        listOfNotNull(LinkEffect.CancelReconnect.takeIf { cancel }),
    )

private fun schedule(
    s: LinkSession,
    after: Int,
    now: Long,
    config: BedJetLink.Config,
): LinkTransition {
    val since = s.downSince ?: now
    val started = s.copy(downSince = since)
    val downFor = now - since
    return when (val decision = reconnectDecision(after, config, downFor)) {
        ReconnectDecision.GiveUp ->
            LinkTransition(
                started.enter(LinkSession.Phase.Down(gaveUp = true)),
                listOf(
                    LinkEffect.Log(
                        LogLevel.ERROR,
                        "Gave up after $after reconnect attempts, down ${downFor / 1000} s",
                    )
                ),
            )
        is ReconnectDecision.Retry -> {
            val max = config.maxReconnectAttempts ?: "∞"
            val waiting = waitFor(started, decision.attempt, decision.waitMs)
            waiting.copy(
                effects =
                    listOf(
                        LinkEffect.Log(
                            LogLevel.WARN,
                            "Reconnect ${decision.attempt}/$max in ${decision.waitMs / 1000} s",
                        )
                    ) + waiting.effects
            )
        }
    }
}

private fun waitFor(s: LinkSession, attempt: Int, waitMs: Long) =
    LinkTransition(
        s.enter(LinkSession.Phase.Waiting(attempt, s.nextRun)).copy(nextRun = s.nextRun + 1),
        listOf(LinkEffect.StartReconnect(s.nextRun, waitMs)),
    )
