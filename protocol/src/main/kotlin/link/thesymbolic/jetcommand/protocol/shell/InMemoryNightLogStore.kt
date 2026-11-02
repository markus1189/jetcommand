package link.thesymbolic.jetcommand.protocol.shell

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import link.thesymbolic.jetcommand.protocol.core.NightEvent
import link.thesymbolic.jetcommand.protocol.core.NightSample
import link.thesymbolic.jetcommand.protocol.ports.NightLogStore

class InMemoryNightLogStore : NightLogStore {
    private val events = MutableStateFlow<List<NightEvent>>(emptyList())
    val all: List<NightEvent>
        get() = events.value

    val samples = mutableListOf<NightSample>()

    override suspend fun append(event: NightEvent) {
        events.value += event
    }

    override suspend fun appendSample(sample: NightSample) {
        samples += sample
    }

    override fun recent(limit: Int) = events.map { it.asReversed().take(limit) }

    override suspend fun events(from: Long, to: Long) =
        events.value.filter { it.at in from until to }.sortedBy { it.at }
}
