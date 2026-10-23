package link.thesymbolic.jetcommand.night

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import link.thesymbolic.jetcommand.protocol.Mode
import link.thesymbolic.jetcommand.protocol.NightDetail
import link.thesymbolic.jetcommand.protocol.NightEvent
import link.thesymbolic.jetcommand.protocol.NightEvent.Kind
import link.thesymbolic.jetcommand.protocol.NightSample
import link.thesymbolic.jetcommand.protocol.NightSummary
import link.thesymbolic.jetcommand.protocol.NightSummary.Cause
import link.thesymbolic.jetcommand.protocol.NightSummary.Gap
import link.thesymbolic.jetcommand.protocol.NightsSource
import link.thesymbolic.jetcommand.protocol.formatTemp
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * Stand-in until the Room-backed source lands: the first real night (a killed app, four link drops,
 * the fan walked down by hand), a clean night before it, and a night still being recorded.
 */
class FakeNightsSource(today: LocalDate = LocalDate.now(), private val now: Long = System.currentTimeMillis()) : NightsSource {
    private val zone = ZoneId.systemDefault()
    private fun at(day: LocalDate, h: Int, m: Int, s: Int = 0) = day.atTime(LocalTime.of(h, m, s)).atZone(zone).toInstant().toEpochMilli()

    private val details: List<NightDetail> = listOf(tonight(), rough(today.minusDays(1)), clean(today.minusDays(2)))

    override fun nights(): Flow<List<NightSummary>> = flowOf(details.map { it.summary })
    override suspend fun detail(night: NightSummary): NightDetail = details.first { it.summary == night }

    private fun tonight(): NightDetail {
        val start = now - 50 * MIN
        val samples = (0..49).map { sample(start + it * MIN, room = 47 - it / 25, air = 38, target = 44, fan = 9, mode = Mode.COOL) }
        val events = listOf(
            NightEvent(start, Kind.CONNECTED, "BEDJET-CD9A"),
            NightEvent(start, Kind.MODE, "Cool", Mode.COOL.ordinal),
            NightEvent(start, Kind.TARGET, "22", 44),
            NightEvent(start, Kind.FAN, "50%", 9),
        )
        return NightDetail(NightSummary(start, samples.last().at, ongoing = true, drops = 0, gaps = emptyList(), presses = 0, killedAt = emptyList()), events, samples)
    }

    private fun rough(day: LocalDate): NightDetail {
        val next = day.plusDays(1)
        val start = at(day, 21, 30)
        val events = mutableListOf(
            NightEvent(start, Kind.PROCESS, "process started"),
            NightEvent(start + 4_000, Kind.CONNECTED, "BEDJET-CD9A"),
            NightEvent(start + 5_000, Kind.MODE, "Cool", Mode.COOL.ordinal),
            NightEvent(start + 5_000, Kind.TARGET, "22", 44),
            NightEvent(start + 5_000, Kind.FAN, "100%", 19),
        )
        val drops = listOf(at(day, 21, 40, 10) to 85, at(day, 21, 50, 2) to 27, at(day, 21, 57, 31) to 358, at(day, 22, 6, 12) to 67)
        drops.forEach { (t, s) ->
            events += NightEvent(t, Kind.LOST, "GATT status 8 (timeout)")
            events += NightEvent(t + s * 1000L, Kind.RECONNECTED, "after $s s", 1)
        }
        // 16 fan-down presses, 21:45–22:20, 100 → 20 %; one lands during the 6-minute drop and never arrives.
        val fanPresses = (0 until 16).map { at(day, 21, 45) + it * (35 * MIN / 15) }
        var step = 19
        fanPresses.forEach { t ->
            if (drops.none { (d, s) -> t in d..d + s * 1000L }) {
                events += NightEvent(t, Kind.COMMAND, "fan ${step * 5}% (07 ${"%02x".format(step - 1)})")
                step--
                events += NightEvent(t + 900, Kind.FAN, "${(step + 1) * 5}%", step)
            }
        }
        var late = at(day, 22, 20, 30)
        while (step > 3) { // the press that missed is made up at the end of the run
            val t = late.also { late += 20_000 }
            events += NightEvent(t, Kind.COMMAND, "fan ${step * 5}% (07 ${"%02x".format(step - 1)})")
            step--
            events += NightEvent(t + 900, Kind.FAN, "${(step + 1) * 5}%", step)
        }
        listOf(at(day, 22, 22, 4) to 43, at(day, 22, 22, 9) to 42).forEach { (t, v) ->
            events += NightEvent(t, Kind.COMMAND, "target ${formatTemp(v)} (03 ${"%02x".format(v)})")
            events += NightEvent(t + 900, Kind.TARGET, formatTemp(v), v)
        }
        val killed = at(day, 22, 55, 54)
        val back = at(next, 4, 56, 10)
        events += NightEvent(killed, Kind.PROCESS, "exit signaled importance 125: (signal 9)")
        events += NightEvent(back, Kind.PROCESS, "process started")
        events += NightEvent(back + 5_000, Kind.CONNECTED, "BEDJET-CD9A")
        events += NightEvent(back + 6_000, Kind.MODE, "Off", Mode.STANDBY.ordinal)
        events.sortBy { it.at }

        val gaps = drops.map { (t, s) -> Gap(t, t + s * 1000L, Cause.LINK) } + Gap(killed, back + 5_000, Cause.APP)
        val samples = buildList {
            var t = start + 5_000
            while (t < killed) {
                if (gaps.none { t in it.from..it.to }) {
                    val m = ((t - start) / MIN).toInt()
                    val fan = events.lastOrNull { it.kind == Kind.FAN && it.at <= t }?.value ?: 19
                    val target = events.lastOrNull { it.kind == Kind.TARGET && it.at <= t }?.value ?: 44
                    add(sample(t, room = 49 - (m * 5 / 85).coerceAtMost(5), air = 33 + (19 - fan) / 3 + wobble(m), target = target, fan = fan, mode = Mode.COOL))
                }
                t += MIN
            }
            (0..1).forEach { add(sample(back + 10_000 + it * MIN, room = 42, air = 42, target = 42, fan = 3, mode = Mode.STANDBY)) }
        }
        val end = maxOf(events.last().at, samples.last().at)
        return NightDetail(NightSummary(start, end, ongoing = false, drops = 4, gaps = gaps, presses = 18, killedAt = listOf(killed)), events, samples)
    }

    private fun clean(day: LocalDate): NightDetail {
        val next = day.plusDays(1)
        val start = at(day, 22, 10)
        val end = at(next, 6, 40)
        val events = listOf(
            NightEvent(start, Kind.CONNECTED, "BEDJET-CD9A"),
            NightEvent(start, Kind.MODE, "Heat", Mode.HEAT.ordinal),
            NightEvent(start, Kind.TARGET, "26", 52),
            NightEvent(start, Kind.FAN, "60%", 11),
            NightEvent(at(day, 23, 10), Kind.COMMAND, "cool (01 0b)"),
            NightEvent(at(day, 23, 10, 1), Kind.MODE, "Cool", Mode.COOL.ordinal),
            NightEvent(at(day, 23, 10, 1), Kind.TARGET, "21", 42),
            NightEvent(at(day, 23, 11), Kind.COMMAND, "fan 40% (07 07)"),
            NightEvent(at(day, 23, 11, 1), Kind.FAN, "40%", 7),
            NightEvent(at(next, 6, 30), Kind.COMMAND, "off (01 01)"),
            NightEvent(at(next, 6, 30, 1), Kind.MODE, "Off", Mode.STANDBY.ordinal),
            NightEvent(end, Kind.DISCONNECTED, "by the app"),
        )
        val samples = generateSequence(start) { it + MIN }.takeWhile { it < end }.map { t ->
            val m = ((t - start) / MIN).toInt()
            val mode = events.last { it.kind == Kind.MODE && it.at <= t }.let { Mode.entries[it.value!!] }
            val target = events.last { it.kind == Kind.TARGET && it.at <= t }.value!!
            val fan = events.last { it.kind == Kind.FAN && it.at <= t }.value!!
            val room = 41 + (2 * cos(m / 90.0)).roundToInt() + if (mode == Mode.HEAT) 3 else 0
            val air = when (mode) { Mode.HEAT -> 60; Mode.COOL -> 36 + wobble(m); else -> room }
            sample(t, room, air, target, fan, mode)
        }.toList()
        return NightDetail(NightSummary(start, end, ongoing = false, drops = 0, gaps = emptyList(), presses = 3, killedAt = emptyList()), events, samples)
    }

    private fun sample(at: Long, room: Int, air: Int, target: Int, fan: Int, mode: Mode) = NightSample(at, room, air, target, fan, mode)
    private fun wobble(m: Int) = listOf(0, 1, 1, 0, -1, 0, 1)[m % 7]

    private companion object { const val MIN = 60_000L }
}
