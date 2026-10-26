package link.thesymbolic.jetcommand.protocol

import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.orNull
import io.kotest.property.checkAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ReconnectDecisionTest {
    private val default = BedJetLink.Config()

    private fun retry(attempt: Int, waitMs: Long) = ReconnectDecision.Retry(attempt, waitMs)

    @Test
    fun `backoff doubles from 3 s up to 60 s, then gives up after 10`() {
        val expected =
            listOf(
                0 to retry(1, 3_000),
                1 to retry(2, 6_000),
                2 to retry(3, 12_000),
                3 to retry(4, 24_000),
                4 to retry(5, 48_000),
                5 to retry(6, 60_000),
                9 to retry(10, 60_000),
                10 to ReconnectDecision.GiveUp,
                11 to ReconnectDecision.GiveUp,
            )
        for ((previous, decision) in expected) assertEquals(
            decision,
            reconnectDecision(previous, default),
            "after $previous",
        )
    }

    @Test
    fun `without a maximum the wait stays capped however long it goes on`() {
        val forever = default.copy(maxReconnectAttempts = null)
        assertEquals(retry(41, 60_000), reconnectDecision(40, forever))
        assertEquals(retry(Int.MAX_VALUE, 60_000), reconnectDecision(Int.MAX_VALUE - 1, forever))
    }

    @Test
    fun `a maximum of 0 gives up at once`() {
        assertEquals(
            ReconnectDecision.GiveUp,
            reconnectDecision(0, default.copy(maxReconnectAttempts = 0)),
        )
    }

    @Test
    fun `gives up once down for the limit, whatever the attempt count`() {
        val limited = default.copy(maxReconnectAttempts = null, giveUpAfterDownMs = 900_000)
        assertEquals(retry(51, 60_000), reconnectDecision(50, limited, downForMs = 899_999))
        assertEquals(ReconnectDecision.GiveUp, reconnectDecision(1, limited, downForMs = 900_000))
    }

    // Bases up to 2^32 ms: beyond that `shl 30` overflows Long, as it always has.
    private val configs =
        Arb.bind(Arb.long(1L..(1L shl 32)), Arb.long(1L..(1L shl 40)), Arb.int(0..50).orNull()) {
            base,
            cap,
            attempts ->
            default.copy(
                reconnectBaseMs = base,
                reconnectMaxMs = cap,
                maxReconnectAttempts = attempts,
            )
        }

    @Test
    fun `the wait never shrinks and never passes the cap`() = runTest {
        checkAll(configs, Arb.int(0..200)) { config, previous ->
            val now = reconnectDecision(previous, config.copy(maxReconnectAttempts = null))
            val next = reconnectDecision(previous + 1, config.copy(maxReconnectAttempts = null))
            now as ReconnectDecision.Retry
            next as ReconnectDecision.Retry
            assertTrue(now.waitMs <= next.waitMs, "$config after $previous: $now then $next")
            assertTrue(next.waitMs <= config.reconnectMaxMs)
            assertEquals(previous + 1, now.attempt)
        }
    }

    @Test
    fun `gives up exactly when the attempts reach the maximum`() = runTest {
        checkAll(configs, Arb.int(0..200)) { config, previous ->
            val max = config.maxReconnectAttempts
            val gaveUp = reconnectDecision(previous, config) == ReconnectDecision.GiveUp
            assertEquals(max != null && previous >= max, gaveUp, "$max after $previous")
        }
    }
}
