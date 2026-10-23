package link.thesymbolic.jetcommand.protocol

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A night is the local day shifted by -12 h: noon to noon, so 18:00–12:00 never splits and stray daytime items go to the night they follow. */
fun nightOf(at: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(at).atZone(zone).minusHours(12).toLocalDate()

/** Epoch millis of [night]'s noon-to-noon window, end exclusive. */
fun nightWindow(night: LocalDate, zone: ZoneId): LongRange {
    val from = night.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    val to = night.plusDays(1).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    return from until to
}

/** Longer than the 1-minute sample interval and a typical reconnect, short enough that yesterday's night ends by breakfast. */
const val ONGOING_QUIET_MS = 30 * 60_000L

/**
 * Newest first. [sampleTimes] only stretch a night's bounds and date an app death that left no exit record.
 * Ongoing: [now] falls in the night's window and its last item is at most [ONGOING_QUIET_MS] old.
 */
fun groupNights(events: List<NightEvent>, sampleTimes: List<Long>, zone: ZoneId, now: Long): List<NightSummary> {
    val ev = events.groupBy { nightOf(it.at, zone) }
    val sm = sampleTimes.groupBy { nightOf(it, zone) }
    return (ev.keys + sm.keys).sortedDescending().map { night ->
        summarize(ev[night].orEmpty(), sm[night].orEmpty(), now in nightWindow(night, zone))
            .let { it.copy(ongoing = it.ongoing && now - it.end <= ONGOING_QUIET_MS) }
    }
}

private fun isStart(e: NightEvent) = e.kind == NightEvent.Kind.PROCESS && e.detail == ProcessLog.STARTED
private fun isExit(e: NightEvent) = e.kind == NightEvent.Kind.PROCESS && e.detail.startsWith(ProcessLog.EXIT_PREFIX)

/** Sort is stable, so same-millisecond events keep their logged order. */
fun summarize(events: List<NightEvent>, sampleTimes: List<Long>, ongoing: Boolean): NightSummary {
    val evs = events.sortedBy { it.at }
    val samples = sampleTimes.sorted()
    val start = minOf(evs.firstOrNull()?.at ?: Long.MAX_VALUE, samples.firstOrNull() ?: Long.MAX_VALUE)
    val end = maxOf(evs.lastOrNull()?.at ?: Long.MIN_VALUE, samples.lastOrNull() ?: Long.MIN_VALUE)
    val gaps = mutableListOf<NightSummary.Gap>()
    val killed = mutableListOf<Long>()
    var linkDownSince: Long? = null
    var exitAt: Long? = null
    var seenStart = false
    var lastAt: Long? = null // last item of the current process, events or samples
    var si = 0
    for (e in evs) {
        if (isStart(e)) {
            while (si < samples.size && samples[si] < e.at) lastAt = maxOf(lastAt ?: Long.MIN_VALUE, samples[si++])
            // Without an exit record or an earlier start in this night, a start is just the app being opened.
            val died = exitAt ?: lastAt.takeIf { seenStart }
            if (died != null) {
                linkDownSince?.let { if (died > it) gaps += NightSummary.Gap(it, died, NightSummary.Cause.LINK) }
                gaps += NightSummary.Gap(died, e.at, NightSummary.Cause.APP)
                killed += died
            }
            linkDownSince = null
            exitAt = null
            seenStart = true
        } else if (isExit(e)) {
            exitAt = e.at
        } else when (e.kind) {
            NightEvent.Kind.LOST -> if (linkDownSince == null) linkDownSince = e.at
            NightEvent.Kind.RECONNECTED, NightEvent.Kind.CONNECTED -> linkDownSince?.let {
                gaps += NightSummary.Gap(it, e.at, NightSummary.Cause.LINK)
                linkDownSince = null
            }
            NightEvent.Kind.DISCONNECTED -> linkDownSince = null
            else -> {}
        }
        if (!isExit(e)) lastAt = e.at
    }
    // Still down when the log ends: the gap runs to the last thing logged.
    linkDownSince?.let { if (end > it) gaps += NightSummary.Gap(it, end, NightSummary.Cause.LINK) }
    return NightSummary(
        start = start,
        end = end,
        ongoing = ongoing,
        drops = evs.count { it.kind == NightEvent.Kind.LOST },
        gaps = gaps,
        presses = evs.count { it.kind == NightEvent.Kind.COMMAND },
        killedAt = killed,
    )
}
