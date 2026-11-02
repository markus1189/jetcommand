package link.thesymbolic.jetcommand.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import link.thesymbolic.jetcommand.protocol.LinkEffect.CancelReconnect
import link.thesymbolic.jetcommand.protocol.LinkEffect.FollowUp
import link.thesymbolic.jetcommand.protocol.LinkEffect.Log
import link.thesymbolic.jetcommand.protocol.LinkEffect.StartReconnect
import link.thesymbolic.jetcommand.protocol.LinkSession.Phase

class LinkStepTest {
    private val config = BedJetLink.Config()

    private fun step(
        s: LinkSession,
        e: LinkEvent,
        now: Long = 0,
        c: BedJetLink.Config = config,
    ) = linkStep(s, e, now, c)

    private fun at(phase: Phase, downSince: Long? = null) =
        LinkSession(phase, downSince, nextRun = 7)

    private val ready = at(Phase.Ready)
    private val waiting = at(Phase.Waiting(attempt = 2, run = 5), downSince = 100)
    private val reconnecting = at(Phase.Connecting(2, run = 5, reconnect = true), downSince = 100)
    private val callerConnecting = at(Phase.Connecting(0, run = 6, reconnect = false))

    @Test
    fun `connect from idle or down starts a fresh caller run`() {
        for (from in listOf(Phase.Idle, Phase.Down(false), Phase.Down(true))) {
            val t = step(at(from), LinkEvent.Connect)
            assertEquals(Phase.Connecting(0, 7, reconnect = false), t.next.phase, "$from")
            assertEquals(8, t.next.nextRun)
            assertEquals(emptyList(), t.effects)
        }
    }

    @Test
    fun `connect while up or while a caller connects changes nothing`() {
        for (s in listOf(ready, callerConnecting)) assertEquals(
            LinkTransition(s),
            step(s, LinkEvent.Connect),
        )
    }

    @Test
    fun `connect takes over a waiting or running reconnect, keeping its count`() {
        for (s in listOf(waiting, reconnecting)) {
            val t = step(s, LinkEvent.Connect)
            assertEquals(Phase.Connecting(2, 7, reconnect = false), t.next.phase)
            assertEquals(100L, t.next.downSince)
            assertEquals(listOf(CancelReconnect), t.effects)
        }
    }

    @Test
    fun `a due reconnect claims only its own waiting run`() {
        assertEquals(
            Phase.Connecting(2, 5, reconnect = true),
            step(waiting, LinkEvent.ReconnectDue(5)).next.phase,
        )
        assertEquals(LinkTransition(waiting), step(waiting, LinkEvent.ReconnectDue(4)))
        assertEquals(LinkTransition(ready), step(ready, LinkEvent.ReconnectDue(5)))
    }

    @Test
    fun `an aborted run goes down only while it still owns the session`() {
        val mine = reconnecting.phase as Phase.Connecting
        assertEquals(
            Phase.Down(false),
            step(reconnecting, LinkEvent.ConnectAborted(mine)).next.phase,
        )
        assertEquals(
            LinkTransition(callerConnecting),
            step(callerConnecting, LinkEvent.ConnectAborted(mine)),
        )
        assertEquals(
            LinkTransition(reconnecting),
            step(reconnecting, LinkEvent.ConnectAborted(mine.copy(attached = true))),
        )
    }

    @Test
    fun `a dropped link goes down, records why, and schedules a reconnect after its attempt`() {
        val t = step(reconnecting, LinkEvent.Dropped("gone"))
        assertEquals(at(Phase.Down(false), downSince = 100), t.next)
        assertEquals(
            listOf(
                Log(LogLevel.WARN, "Connection lost: gone"),
                LinkEffect.RecordDropReason("gone"),
                LinkEffect.ResetAssembler,
                FollowUp(LinkEvent.Schedule(2)),
            ),
            t.effects,
        )
        assertEquals(
            FollowUp(LinkEvent.Schedule(0)),
            step(ready, LinkEvent.Dropped("x")).effects.last(),
        )
    }

    @Test
    fun `without auto-reconnect a drop stays down`() {
        val t = step(ready, LinkEvent.Dropped("x"), c = config.copy(autoReconnect = false))
        assertEquals(Phase.Down(false), t.next.phase)
        assertEquals(LinkEffect.ResetAssembler, t.effects.last())
    }

    @Test
    fun `a drop while idle, down or waiting is not ours`() {
        for (s in listOf(at(Phase.Idle), at(Phase.Down(true)), waiting)) assertEquals(
            LinkTransition(s),
            step(s, LinkEvent.Dropped("x")),
        )
    }

    @Test
    fun `schedule starts the give-up clock and waits with backoff`() {
        val t = step(at(Phase.Down(false)), LinkEvent.Schedule(1), now = 500)
        assertEquals(LinkSession(Phase.Waiting(2, 7), downSince = 500, nextRun = 8), t.next)
        assertEquals(
            listOf(Log(LogLevel.WARN, "Reconnect 2/10 in 6 s"), StartReconnect(7, 6000)),
            t.effects,
        )
    }

    @Test
    fun `schedule shows an unlimited maximum as infinity`() {
        val t =
            step(
                at(Phase.Down(false)),
                LinkEvent.Schedule(0),
                c = config.copy(maxReconnectAttempts = null),
            )
        assertEquals(Log(LogLevel.WARN, "Reconnect 1/∞ in 3 s"), t.effects.first())
    }

    @Test
    fun `schedule gives up at the maximum attempt`() {
        val down = at(Phase.Down(false), downSince = 0)
        assertEquals(Phase.Waiting(10, 7), step(down, LinkEvent.Schedule(9)).next.phase)
        val t = step(down, LinkEvent.Schedule(10), now = 42_000)
        assertEquals(Phase.Down(gaveUp = true), t.next.phase)
        assertEquals(
            listOf(Log(LogLevel.ERROR, "Gave up after 10 reconnect attempts, down 42 s")),
            t.effects,
        )
    }

    @Test
    fun `schedule gives up once down for giveUpAfterDownMs, counted from the first drop`() {
        val c = config.copy(maxReconnectAttempts = null, giveUpAfterDownMs = 10_000)
        val down = at(Phase.Down(false), downSince = 1_000)
        assertEquals(
            Phase.Waiting(4, 7),
            step(down, LinkEvent.Schedule(3), now = 10_999, c = c).next.phase,
        )
        assertEquals(
            Phase.Down(gaveUp = true),
            step(down, LinkEvent.Schedule(3), now = 11_000, c = c).next.phase,
        )
    }

    @Test
    fun `a failed caller connect retries only when owned, retryFailedConnect and autoReconnect`() {
        val on = config.copy(retryFailedConnect = true)
        val s = at(Phase.Down(false))
        assertEquals(
            listOf(
                Log(LogLevel.WARN, "Connect failed: boom"),
                FollowUp(LinkEvent.Schedule(3)),
            ),
            step(s, LinkEvent.ConnectFailed(3, owned = true, "boom"), c = on).effects,
        )
        for ((owned, c) in
            listOf(
                false to on,
                true to config,
                true to on.copy(autoReconnect = false),
            )) assertEquals(
            LinkTransition(s),
            step(s, LinkEvent.ConnectFailed(3, owned, "boom"), c = c),
        )
    }

    @Test
    fun `a failed reconnect always logs and schedules only when owned`() {
        val s = at(Phase.Down(false))
        val log = Log(LogLevel.WARN, "Reconnect 4 failed: boom")
        assertEquals(
            listOf(log, FollowUp(LinkEvent.Schedule(4))),
            step(s, LinkEvent.ReconnectFailed(4, owned = true, "boom")).effects,
        )
        assertEquals(
            LinkTransition(s, listOf(log)),
            step(s, LinkEvent.ReconnectFailed(4, owned = false, "boom")),
        )
    }

    @Test
    fun `retry now restarts the backoff from a wait or after giving up, resetting the clock`() {
        val t = step(waiting, LinkEvent.RetryNow)
        assertEquals(LinkSession(Phase.Waiting(1, 7), downSince = null, nextRun = 8), t.next)
        assertEquals(
            listOf(Log(LogLevel.INFO, "Retrying now"), CancelReconnect, StartReconnect(7, 0)),
            t.effects,
        )
        val gaveUp = step(at(Phase.Down(true), downSince = 5), LinkEvent.RetryNow)
        assertEquals(Phase.Waiting(1, 7), gaveUp.next.phase)
        assertEquals(
            listOf(Log(LogLevel.INFO, "Retrying now"), StartReconnect(7, 0)),
            gaveUp.effects,
        )
    }

    @Test
    fun `retry now does nothing elsewhere or without auto-reconnect`() {
        for (s in listOf(at(Phase.Idle), at(Phase.Down(false)), ready, reconnecting)) assertEquals(
            LinkTransition(s),
            step(s, LinkEvent.RetryNow),
        )
        assertEquals(
            LinkTransition(waiting),
            step(waiting, LinkEvent.RetryNow, c = config.copy(autoReconnect = false)),
        )
    }

    @Test
    fun `disconnect goes idle, stops the clock, and cancels only a reconnect`() {
        for (s in listOf(waiting, reconnecting)) assertEquals(
            LinkTransition(at(Phase.Idle), listOf(CancelReconnect)),
            step(s, LinkEvent.Disconnect),
        )
        for (s in
            listOf(ready, callerConnecting, at(Phase.Down(true), downSince = 3))) assertEquals(
            LinkTransition(at(Phase.Idle)),
            step(s, LinkEvent.Disconnect),
        )
    }

    @Test
    fun `ready and idle stop the give-up clock, other phases keep it`() {
        val s = at(Phase.Down(false), downSince = 9)
        assertEquals(null, s.enter(Phase.Ready).downSince)
        assertEquals(null, s.enter(Phase.Idle).downSince)
        assertEquals(9L, s.enter(Phase.Waiting(1, 1)).downSince)
    }
}
