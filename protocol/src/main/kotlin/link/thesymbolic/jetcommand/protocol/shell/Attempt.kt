package link.thesymbolic.jetcommand.protocol.shell

import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] for suspend code: a [CancellationException] is rethrown, so the caller's
 * cancellation still propagates, and every other failure goes to [onError] before it is returned.
 */
suspend inline fun <T> attempt(
    onError: (Throwable) -> Unit = {},
    block: suspend () -> T,
): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        onError(e)
        Result.failure(e)
    }

/**
 * Runs [cleanup] when [block] fails in any way, cancellation included, then lets the failure
 * propagate. Inline, so [cleanup] may suspend inside a suspending caller.
 */
inline fun <T> cleanupOnFailure(cleanup: () -> Unit, block: () -> T): T {
    var ok = false
    try {
        return block().also { ok = true }
    } finally {
        if (!ok) cleanup()
    }
}
