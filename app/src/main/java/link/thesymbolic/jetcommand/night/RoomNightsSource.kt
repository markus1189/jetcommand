package link.thesymbolic.jetcommand.night

import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import link.thesymbolic.jetcommand.protocol.NightDetail
import link.thesymbolic.jetcommand.protocol.NightSummary
import link.thesymbolic.jetcommand.protocol.NightsSource
import link.thesymbolic.jetcommand.protocol.groupNights
import link.thesymbolic.jetcommand.protocol.nightOf
import link.thesymbolic.jetcommand.protocol.nightWindow

/**
 * Regroups the whole log on every write (a sample a minute): events are a few hundred a night and
 * samples are read as times only, so this stays cheap for months; cache finished nights if it ever
 * shows up.
 */
class RoomNightsSource(
    private val dao: NightEventDao,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val now: () -> Long = System::currentTimeMillis,
) : NightsSource {
    override fun nights(): Flow<List<NightSummary>> =
        // The tick ends "ongoing" when the log goes quiet, which no write would signal.
        combine(dao.allEvents(), dao.allSampleTimes(), minuteTicks()) { events, samples, _ ->
                groupNights(events.mapNotNull { it.toEvent() }, samples, zone(), now())
            }
            .conflate()

    private fun minuteTicks() = flow {
        while (true) {
            emit(Unit)
            delay(60_000)
        }
    }

    // The whole window, not start..end: an ongoing night has grown since its summary was taken.
    override suspend fun detail(night: NightSummary): NightDetail {
        val window = nightWindow(nightOf(night.start, zone()), zone())
        val to = window.last + 1
        return NightDetail(
            night,
            dao.events(window.first, to).mapNotNull { it.toEvent() },
            dao.samples(window.first, to).mapNotNull { it.toSample() },
        )
    }
}
