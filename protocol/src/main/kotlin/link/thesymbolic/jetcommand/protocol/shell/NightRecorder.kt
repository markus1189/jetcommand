package link.thesymbolic.jetcommand.protocol.shell

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import link.thesymbolic.jetcommand.protocol.core.NightEvent
import link.thesymbolic.jetcommand.protocol.core.NightSample
import link.thesymbolic.jetcommand.protocol.core.RecorderInput
import link.thesymbolic.jetcommand.protocol.core.RecorderOutput
import link.thesymbolic.jetcommand.protocol.core.RecorderState
import link.thesymbolic.jetcommand.protocol.core.describeCommand
import link.thesymbolic.jetcommand.protocol.core.next
import link.thesymbolic.jetcommand.protocol.core.toHex
import link.thesymbolic.jetcommand.protocol.core.u
import link.thesymbolic.jetcommand.protocol.ports.BedJetTransport
import link.thesymbolic.jetcommand.protocol.ports.Characteristic
import link.thesymbolic.jetcommand.protocol.ports.NightLogStore

/** Passes [BedJetTransport.write]s of commands (not queries) to [onCommand] before sending them. */
class TappedTransport(
    private val inner: BedJetTransport,
    private val onCommand: (ByteArray) -> Unit,
) : BedJetTransport by inner {
    override suspend fun write(characteristic: Characteristic, bytes: ByteArray) {
        if (characteristic == Characteristic.COMMAND && bytes.isNotEmpty() && bytes.u(0) != 0x41)
            onCommand(bytes.copyOf())
        inner.write(characteristic, bytes)
    }
}

/**
 * Turns one link's states and statuses into [NightEvent]s: changes only, since the unit notifies
 * ~4/s. Step and notification come from complete packets only; the first status after a reconnect
 * is diffed against the last one before the loss, so what changed in the gap is recorded once.
 * Samples are taken from arriving statuses at most once per [sampleMs], so a dropped link leaves a
 * gap rather than repeated stale values. Same threading rule as [BedJetLink].
 */
class NightRecorder(
    private val link: BedJetLink,
    private val store: NightLogStore,
    scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sampleMs: Long = 60_000,
) {
    private val queue = Channel<NightEvent>(Channel.UNLIMITED)
    private val sampleQueue = Channel<NightSample>(Channel.UNLIMITED)
    private var state = RecorderState(link.state.value)

    init {
        // One writer keeps the store in event order even when append suspends on another thread.
        scope.launch { for (e in queue) store.append(e) }
        scope.launch { for (s in sampleQueue) store.appendSample(s) }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            link.state.collect {
                feed(
                    RecorderInput.Link(
                        clock(),
                        it,
                        link.deviceName,
                        link.reconnectAttempt,
                        link.lastDropReason,
                    )
                )
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            link.statuses.collect { feed(RecorderInput.Update(clock(), it)) }
        }
    }

    /** Call before [BedJetLink.disconnect], so the drop is recorded as asked for, not lost. */
    fun userDisconnecting() = feed(RecorderInput.UserDisconnecting)

    fun command(bytes: ByteArray) {
        queue.trySend(
            NightEvent(
                clock(),
                NightEvent.Kind.COMMAND,
                "${describeCommand(bytes)} (${bytes.toHex()})",
            )
        )
    }

    private fun feed(input: RecorderInput) {
        val (next, out) = state.next(input, sampleMs)
        state = next
        out.forEach {
            when (it) {
                is RecorderOutput.Event -> queue.trySend(it.event)
                is RecorderOutput.Sample -> sampleQueue.trySend(it.sample)
            }
        }
    }
}
