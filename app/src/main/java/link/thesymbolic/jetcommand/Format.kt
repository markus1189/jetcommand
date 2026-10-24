// Clocks, durations and counts as every screen words them; ASCII digits whatever the locale.
package link.thesymbolic.jetcommand

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

fun pad2(n: Int) = n.toString().padStart(2, '0')

/** Wall clock, 24 h: "07:05". */
fun hhmm(hour: Int, minute: Int) = "${pad2(hour)}:${pad2(minute)}"

fun hhmm(t: LocalTime) = hhmm(t.hour, t.minute)

fun hhmm(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()) = hhmm(localTime(epochMs, zone))

fun hhmmss(epochMs: Long, zone: ZoneId) =
    localTime(epochMs, zone).let { "${hhmm(it)}:${pad2(it.second)}" }

private fun localTime(epochMs: Long, zone: ZoneId) =
    Instant.ofEpochMilli(epochMs).atZone(zone).toLocalTime()

/** The wall clock [minutes] after [start]. */
fun clockAt(start: LocalTime, minutes: Int) = hhmm(start.plusMinutes(minutes.toLong()))

/** "1:05": unpadded hours, an amount rather than a time of day. */
fun hoursMinutes(seconds: Int) = "${seconds / 3600}:${pad2(seconds / 60 % 60)}"

/** "00:14:37": a countdown, padded like the BedJet app's timer so its width never jumps. */
fun countdown(seconds: Int) =
    "${pad2(seconds / 3600)}:${pad2(seconds / 60 % 60)}:${pad2(seconds % 60)}"

/** A step's length: "45 min", "1 h 05". */
fun durationMin(minutes: Int): String =
    if (minutes < 60) "$minutes min" else "${minutes / 60} h ${pad2(minutes % 60)}"

/** Elapsed time rounded to its unit: "90 s" under two minutes, then "12 min", "2 h", "1 h 30 m". */
fun durationMs(ms: Long): String {
    val m = (ms + 30_000) / 60_000
    return when {
        ms < 120_000 -> "${(ms + 500) / 1000} s"
        m < 60 -> "$m min"
        m % 60 == 0L -> "${m / 60} h"
        else -> "${m / 60} h ${m % 60} m"
    }
}

fun plural(n: Int, word: String, plural: String = word + "s") = "$n ${if (n == 1) word else plural}"
