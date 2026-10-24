package link.thesymbolic.jetcommand.protocol

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class AttemptTest {
    @Test
    fun `failures are returned and reported`() = runTest {
        var seen: Throwable? = null
        val r = attempt(onError = { seen = it }) { error("boom") }
        assertTrue(r.isFailure)
        assertEquals("boom", seen?.message)
    }

    @Test
    fun `cancellation is rethrown, not reported`() = runTest {
        var reported = false
        assertFailsWith<CancellationException> {
            attempt(onError = { reported = true }) { throw CancellationException("stop") }
        }
        assertTrue(!reported)
    }
}
