package link.thesymbolic.jetcommand.protocol

import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.orNull
import io.kotest.property.checkAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class SlotStatusTest {
    // Few programs, so random catalogs and slots collide on content often.
    private val pool =
        List(4) { i ->
            Program("P$i", listOf(Step(Mode.COOL, i, 40, Timing.For(1, 0))))
        }
    private val p0 = pool[0]
    private val p1 = pool[1]
    private val p2 = pool[2]
    private val p3 = pool[3]

    private fun rev(id: String, n: Int, p: Program, origin: Origin = Origin.EDIT) =
        ProgramRevision(id, n, p, createdAt = n.toLong(), origin)

    // "alpha": r1 = p0 (EDIT), r2 = p1 (OUTSIDE), r3 = p2 (OUTSIDE), r4 = p3 current.
    private val alphaRevs =
        listOf(
            rev("alpha", 1, p0),
            rev("alpha", 2, p1, Origin.OUTSIDE),
            rev("alpha", 3, p2, Origin.OUTSIDE),
            rev("alpha", 4, p3),
        )
    private val alpha = LibraryEntry("alpha", alphaRevs.last())
    private val gone = LibraryEntry("gone", rev("gone", 1, p0), deletedAt = 5)
    private val catalog = Catalog(listOf(alpha, gone), alphaRevs + gone.current)

    private fun read(p: Program?, pushed: PushRecord? = null, pending: PushRecord? = null) =
        SlotState("d", Slot.all[0], p, readAt = 1, pushed = pushed, pending = pending)

    private fun push(id: String, rev: Int, p: Program?) =
        PushRecord(id, rev, p?.fingerprint() ?: "none")

    @Test
    fun `every branch`() {
        val cases =
            listOf(
                "never read" to
                    (SlotState("d", Slot.all[0], p0, readAt = null) to SlotStatus.Unknown),
                "empty" to (read(null) to SlotStatus.Empty),
                "pending not on the unit" to
                    (read(p0, pending = push("alpha", 4, p3)) to
                        SlotStatus.Interrupted(push("alpha", 4, p3))),
                "pending, slot empty" to
                    (read(null, pending = push("alpha", 4, p3)) to
                        SlotStatus.Interrupted(push("alpha", 4, p3))),
                "never pushed" to (read(p3) to SlotStatus.NotInLibrary(p3)),
                "pushed program unknown" to
                    (read(p3, push("ghost", 1, p3)) to SlotStatus.NotInLibrary(p3)),
                "linked entry tombstoned" to
                    (read(p0, push("gone", 1, p0)) to SlotStatus.Deleted(gone)),
                "holds current" to (read(p3, push("alpha", 1, p0)) to SlotStatus.InSync(alpha)),
                "pending matches: settles like no pending" to
                    (read(p3, push("alpha", 4, p3), pending = push("alpha", 4, p3)) to
                        SlotStatus.InSync(alpha)),
                "holds what was pushed, edited since" to
                    (read(p0, push("alpha", 1, p0)) to SlotStatus.Older(alpha, 1)),
                "holds an outside revision" to
                    (read(p1, push("alpha", 1, p0)) to SlotStatus.ChangedOutside(alpha, 2, p1)),
                "holds a non-outside revision it wasn't pushed" to
                    (read(p0, push("alpha", 2, p1)) to SlotStatus.NotInLibrary(p0)),
            )
        cases.forEach { (name, case) ->
            assertEquals(case.second, slotStatus(case.first, catalog), name)
        }
    }

    @Test
    fun `outside lookup takes the newest outside revision with that content`() {
        val revs = alphaRevs + rev("alpha", 5, p1, Origin.OUTSIDE)
        val c = Catalog(listOf(alpha), revs)
        assertEquals(
            SlotStatus.ChangedOutside(alpha, 5, p1),
            slotStatus(read(p1, push("alpha", 1, p0)), c),
        )
    }

    @Test
    fun `revisions of programs missing from entries don't count`() {
        val c = Catalog(emptyList(), alphaRevs)
        assertEquals(false, c.holds(p0.fingerprint()))
    }

    @Test
    fun `each revision is hashed once per catalog`() {
        var calls = 0
        val c =
            Catalog(listOf(alpha, gone), alphaRevs + gone.current) {
                calls++
                it.program.fingerprint()
            }
        repeat(10) { slotStatus(read(p1, push("alpha", 1, p0)), c) }
        assertEquals(alphaRevs.size + 1, calls)
    }

    private val ids = listOf("a", "b", "c")

    private val catalogs =
        Arb.bind(
            Arb.list(
                Arb.bind(
                    Arb.list(Arb.bind(Arb.element(pool), Arb.enum<Origin>(), ::Pair), 1..3),
                    Arb.int(0..2),
                    Arb.boolean(),
                    ::Triple,
                ),
                3..3,
            ),
            Arb.int(0..3),
        ) { specs, count ->
            val built =
                specs.take(count).mapIndexed { i, (revs, cur, deleted) ->
                    val id = ids[i]
                    val all = revs.mapIndexed { n, (p, o) -> rev(id, n + 1, p, o) }
                    LibraryEntry(id, all[cur.coerceAtMost(all.lastIndex)], 1L.takeIf { deleted }) to
                        all
                }
            Catalog(built.map { it.first }, built.flatMap { it.second })
        }

    private val records =
        Arb.bind(Arb.element(ids + "ghost"), Arb.int(1..3), Arb.element(pool + null)) { id, r, p ->
            push(id, r, p)
        }

    private val states =
        Arb.bind(
            Arb.element(pool + null),
            Arb.boolean(),
            records.orNull(0.3),
            records.orNull(0.7),
        ) { p, read, pushed, pending ->
            SlotState("d", Slot.all[0], p, if (read) 1 else null, pushed, pending)
        }

    @Test
    fun `InSync exactly when the slot holds its linked live entry's current revision`() = runTest {
        checkAll(catalogs, states) { catalog, state ->
            val status = slotStatus(state, catalog)
            val fp = state.program?.fingerprint()
            val settled =
                state.readAt != null && state.pending.let { it == null || it.fingerprint == fp }
            val linked =
                state.pushed?.let { catalog.entry(it.programId) }?.takeIf { it.deletedAt == null }
            val expected =
                settled &&
                    fp != null &&
                    linked != null &&
                    linked.current.program.fingerprint() == fp
            assertEquals(expected, status is SlotStatus.InSync, "$state -> $status")
        }
    }
}
