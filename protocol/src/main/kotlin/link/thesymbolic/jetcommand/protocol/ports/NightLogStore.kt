package link.thesymbolic.jetcommand.protocol.ports

import kotlinx.coroutines.flow.Flow
import link.thesymbolic.jetcommand.protocol.core.NightEvent
import link.thesymbolic.jetcommand.protocol.core.NightSample

interface NightLogStore {
    suspend fun append(event: NightEvent)

    suspend fun appendSample(sample: NightSample)

    /** Newest first. */
    fun recent(limit: Int): Flow<List<NightEvent>>

    /** [from] inclusive, [to] exclusive, oldest first. */
    suspend fun events(from: Long, to: Long): List<NightEvent>
}
