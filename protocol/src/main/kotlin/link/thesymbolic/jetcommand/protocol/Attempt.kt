package link.thesymbolic.jetcommand.protocol

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
