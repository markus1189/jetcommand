package link.thesymbolic.jetcommand.protocol.core

/**
 * One night: everything logged from [start] to [end]; [end] is the last event or sample, not a
 * wall-clock bound. [gaps], in time order, are the stretches nothing was watching: the link lost,
 * or the app's process gone.
 */
data class NightSummary(
    val start: Long,
    val end: Long,
    val ongoing: Boolean,
    val drops: Int,
    val gaps: List<Gap>,
    val presses: Int,
    val killedAt: List<Long>,
) {
    data class Gap(val from: Long, val to: Long, val cause: Cause)

    enum class Cause {
        LINK,
        APP,
    }
}

data class NightDetail(
    val summary: NightSummary,
    val events: List<NightEvent>,
    val samples: List<NightSample>,
)
