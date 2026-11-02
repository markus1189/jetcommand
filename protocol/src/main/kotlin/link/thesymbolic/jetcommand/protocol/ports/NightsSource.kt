package link.thesymbolic.jetcommand.protocol.ports

import kotlinx.coroutines.flow.Flow
import link.thesymbolic.jetcommand.protocol.core.NightDetail
import link.thesymbolic.jetcommand.protocol.core.NightSummary

interface NightsSource {
    /** Newest first. */
    fun nights(): Flow<List<NightSummary>>

    suspend fun detail(night: NightSummary): NightDetail
}
