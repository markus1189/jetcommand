package link.thesymbolic.jetcommand.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

class ReconcileSlotTest {
    private val p =
        List(5) { i -> Program("P$i", listOf(Step(Mode.COOL, i, 40, Timing.For(1, 0)))) }

    private fun rev(id: String, n: Int, prog: Program, origin: Origin = Origin.EDIT) =
        ProgramRevision(ProgramId(id), n, prog, createdAt = n.toLong(), origin)

    // "alpha": r1 = p0 (EDIT), r2 = p1 (OUTSIDE), r3 = p2 current. "beta": p3 current.
    // "gone": deleted, r1 = p4.
    private val alphaRevs =
        listOf(rev("alpha", 1, p[0]), rev("alpha", 2, p[1], Origin.OUTSIDE), rev("alpha", 3, p[2]))
    private val alpha = LibraryEntry(ProgramId("alpha"), alphaRevs.last())
    private val beta = LibraryEntry(ProgramId("beta"), rev("beta", 1, p[3]))
    private val gone = LibraryEntry(ProgramId("gone"), rev("gone", 1, p[4]), deletedAt = 5)
    private val catalog =
        Catalog(listOf(alpha, beta, gone), alphaRevs + beta.current + gone.current)

    private val t = 100L

    private fun old(pushed: PushRecord? = null, pending: PushRecord? = null) =
        SlotState(DeviceId("d"), Slot.all[0], p[0], readAt = 1, pushed, pending)

    private fun rec(id: String, rev: Int, prog: Program, at: Long = 0) =
        PushRecord(ProgramId(id), rev, prog.fingerprint(), at)

    private fun read(prog: Program?, pushed: PushRecord?, pending: PushRecord? = null) =
        SlotState(DeviceId("d"), Slot.all[0], prog, readAt = t, pushed, pending)

    private fun check(
        old: SlotState,
        prog: Program?,
        expected: Reconciled,
        cat: Catalog = catalog,
    ) = assertEquals(expected, reconcileSlot(old, prog, cat, t))

    @Test
    fun `pending matched by the read-back becomes pushed`() =
        check(
            old(pushed = rec("beta", 1, p[3]), pending = rec("alpha", 3, p[2])),
            p[2],
            Reconciled(read(p[2], rec("alpha", 3, p[2], at = t))),
        )

    @Test
    fun `pending not matched stays interrupted, catalog ignored`() {
        val o = old(pending = rec("alpha", 3, p[2]))
        val expected = Reconciled(read(p[3], null, pending = rec("alpha", 3, p[2])))
        check(o, p[3], expected)
        check(o, p[3], expected, Catalog(emptyList(), emptyList()))
    }

    @Test
    fun `empty slot keeps the push record`() =
        check(
            old(pushed = rec("alpha", 3, p[2])),
            null,
            Reconciled(read(null, rec("alpha", 3, p[2]))),
        )

    @Test
    fun `nothing pushed links a current match`() =
        check(old(), p[3], Reconciled(read(p[3], rec("beta", 1, p[3], at = t))))

    @Test
    fun `nothing pushed, no current match stays unlinked`() =
        check(old(), p[1], Reconciled(read(p[1], null)))

    @Test
    fun `same fingerprint keeps the record untouched`() {
        val pushed = rec("alpha", 3, p[2], at = 7)
        check(old(pushed = pushed), p[2], Reconciled(read(p[2], pushed)))
    }

    @Test
    fun `missing linked program relinks to a current match or unlinks`() {
        check(
            old(pushed = rec("nope", 1, p[0])),
            p[3],
            Reconciled(read(p[3], rec("beta", 1, p[3], at = t))),
        )
        check(old(pushed = rec("nope", 1, p[0])), p[1], Reconciled(read(p[1], null)))
    }

    @Test
    fun `deleted linked program without that content unlinks`() =
        check(old(pushed = rec("gone", 1, p[4])), p[1], Reconciled(read(p[1], null)))

    @Test
    fun `own current revision links to it`() =
        check(
            old(pushed = rec("alpha", 1, p[0])),
            p[2],
            Reconciled(read(p[2], rec("alpha", 3, p[2], at = t))),
        )

    @Test
    fun `own older non-outside revision links to it`() =
        check(
            old(pushed = rec("alpha", 3, p[2])),
            p[0],
            Reconciled(read(p[0], rec("alpha", 1, p[0], at = t))),
        )

    @Test
    fun `other program's current match wins over an own outside revision`() {
        val cat =
            Catalog(
                listOf(alpha, LibraryEntry(ProgramId("beta"), rev("beta", 1, p[1]))),
                alphaRevs + rev("beta", 1, p[1]),
            )
        check(
            old(pushed = rec("alpha", 3, p[2])),
            p[1],
            Reconciled(read(p[1], rec("beta", 1, p[1], at = t))),
            cat,
        )
    }

    @Test
    fun `own outside revision keeps the record`() {
        val pushed = rec("alpha", 3, p[2])
        check(old(pushed = pushed), p[1], Reconciled(read(p[1], pushed)))
    }

    @Test
    fun `new content saves an outside revision`() {
        val pushed = rec("alpha", 3, p[2])
        check(
            old(pushed = pushed),
            p[4],
            Reconciled(read(p[4], pushed), OutsideRevision(ProgramId("alpha"), p[4], t)),
        )
    }
}
