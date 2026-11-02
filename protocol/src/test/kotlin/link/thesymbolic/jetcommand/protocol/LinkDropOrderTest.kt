package link.thesymbolic.jetcommand.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import link.thesymbolic.jetcommand.protocol.core.LinkState
import link.thesymbolic.jetcommand.protocol.shell.BedJetLink
import link.thesymbolic.jetcommand.protocol.shell.BedJetSim
import link.thesymbolic.jetcommand.protocol.shell.SimTransport

@OptIn(ExperimentalCoroutinesApi::class)
class LinkDropOrderTest {
    @Test
    fun `the drop reason is set before the state leaves Ready`() = runTest {
        val transport = SimTransport(BedJetSim(), backgroundScope)
        val link = BedJetLink(transport, backgroundScope, now = { currentTime })
        link.connect()
        // NightRecorder reads lastDropReason when it sees the state leave Ready.
        val seen = mutableListOf<Pair<LinkState, String?>>()
        // Unconfined: a dispatched collector would only see the conflated end state.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            link.state.collect { seen += it to link.lastDropReason }
        }
        transport.loseLink()
        testScheduler.runCurrent()
        val left = seen.first { it.first != LinkState.Ready }
        assertEquals(LinkState.Disconnected(), left.first)
        assertNotNull(left.second, "lastDropReason when $left was observed")
    }
}
