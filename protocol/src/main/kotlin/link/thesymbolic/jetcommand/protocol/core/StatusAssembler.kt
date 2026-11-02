package link.thesymbolic.jetcommand.protocol.core

/**
 * Puts split status packets back together, without side effects: every input returns the next
 * assembler and what the link should do about it ([Assembled]).
 *
 * [head] is the last partial packet, waiting for its remainder; [extra] the last known bytes 27–29;
 * [lastRemainderRead] when the last remainder read was asked for, null before the first one;
 * [remainderPending] while that read or a remainder notification is still due.
 */
data class StatusAssembler(
    val head: ByteArray? = null,
    val extra: StatusExtra? = null,
    val lastRemainderRead: Long? = null,
    val remainderPending: Boolean = false,
) {
    /** A value notified on [Characteristic.STATUS] at [now] (ms). */
    fun notified(bytes: ByteArray, now: Long, remainderIntervalMs: Long): Assembled =
        when {
            Reply.isStatusStart(bytes) -> head(bytes, now, remainderIntervalMs)
            remainderPending -> merge(bytes)
            else -> Assembled(this)
        }

    /**
     * The value a remainder read returned. Android may also notify it; whichever arrives first
     * merges, so a read that finds nothing pending is dropped.
     */
    fun remainderRead(bytes: ByteArray): Assembled =
        if (remainderPending && !Reply.isStatusStart(bytes)) merge(bytes)
        else Assembled(remainderSettled())

    /** The remainder read ended in any way: failed, cancelled, or done. */
    fun remainderSettled(): StatusAssembler = copy(remainderPending = false)

    /** The connection went down. The read rate limit outlives it. */
    fun reset(): StatusAssembler = StatusAssembler(lastRemainderRead = lastRemainderRead)

    private fun head(bytes: ByteArray, now: Long, remainderIntervalMs: Long): Assembled {
        val status =
            Reply.parseStatus(bytes)
                ?: return Assembled(this, warning = "Unparseable status: ${bytes.toHex()}")
        if (status.extra != null)
            return Assembled(
                copy(extra = status.extra, head = null),
                StatusUpdate(status, extraFresh = true),
            )
        // Bytes 27–29 lag behind: show the last known ones and fetch the remainder now and then.
        val due =
            bytes.u(0) == 1 &&
                !remainderPending &&
                (lastRemainderRead.let { it == null || now - it >= remainderIntervalMs })
        return Assembled(
            copy(
                head = bytes,
                lastRemainderRead = if (due) now else lastRemainderRead,
                remainderPending = remainderPending || due,
            ),
            StatusUpdate(status.copy(extra = extra), extraFresh = false),
            readRemainder = due,
        )
    }

    private fun merge(rest: ByteArray): Assembled {
        val settled = remainderSettled()
        val head = head ?: return Assembled(settled)
        val status = Reply.parseStatus(head + rest)
        if (status?.extra == null)
            return Assembled(
                settled,
                warning = "Status remainder didn't complete the packet: ${rest.toHex()}",
            )
        return Assembled(
            settled.copy(extra = status.extra),
            StatusUpdate(status, extraFresh = true),
        )
    }

    // Arrays compare by identity in a data class.
    override fun equals(other: Any?): Boolean =
        other is StatusAssembler &&
            head.contentEquals(other.head) &&
            extra == other.extra &&
            lastRemainderRead == other.lastRemainderRead &&
            remainderPending == other.remainderPending

    override fun hashCode(): Int =
        listOf(head.contentHashCode(), extra, lastRemainderRead, remainderPending).hashCode()
}

/**
 * One step of a [StatusAssembler]: [next] replaces it; [update] is emitted; [readRemainder] asks
 * for a read of [Characteristic.STATUS], to be fed back through [StatusAssembler.remainderRead];
 * [warning] is logged.
 */
data class Assembled(
    val next: StatusAssembler,
    val update: StatusUpdate? = null,
    val readRemainder: Boolean = false,
    val warning: String? = null,
)
