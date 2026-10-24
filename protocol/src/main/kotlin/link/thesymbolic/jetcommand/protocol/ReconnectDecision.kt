package link.thesymbolic.jetcommand.protocol

sealed interface ReconnectDecision {
    /** Try reconnect number [attempt] after [waitMs]. */
    data class Retry(val attempt: Int, val waitMs: Long) : ReconnectDecision

    data object GiveUp : ReconnectDecision
}

/**
 * What follows reconnect attempt [previous] (0: the link was up, or the caller's own connect
 * failed): exponential backoff from [BedJetLink.Config.reconnectBaseMs], capped at
 * [BedJetLink.Config.reconnectMaxMs], until [BedJetLink.Config.maxReconnectAttempts].
 */
fun reconnectDecision(previous: Int, config: BedJetLink.Config): ReconnectDecision {
    val max = config.maxReconnectAttempts
    if (max != null && previous >= max) return ReconnectDecision.GiveUp
    val attempt = previous + 1
    val wait =
        minOf(config.reconnectBaseMs shl (attempt - 1).coerceAtMost(30), config.reconnectMaxMs)
    return ReconnectDecision.Retry(attempt, wait)
}
