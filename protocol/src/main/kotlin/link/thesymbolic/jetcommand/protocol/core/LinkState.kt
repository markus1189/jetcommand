package link.thesymbolic.jetcommand.protocol.core

sealed interface LinkState {
    /** [gaveUp]: auto-reconnect ran out of attempts or of [LinkConfig.giveUpAfterDownMs]. */
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

/** Timeouts, retries and reconnect policy of a [BedJetLink]. */
data class LinkConfig(
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
