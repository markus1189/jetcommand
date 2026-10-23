package link.thesymbolic.jetcommand.protocol

import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import link.thesymbolic.jetcommand.protocol.SimTransport.Fault
import link.thesymbolic.jetcommand.protocol.SimTransport.Op

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryTest {
    private class Rig(
        val sim: BedJetSim,
        val transport: SimTransport,
        val device: BedJetDevice,
        val store: InMemoryLibraryStore,
        val lib: ProgramLibrary,
    ) {
        suspend fun views() = lib.slots(DEV).first()

        suspend fun status(slot: Int) = views()[slot].status

        suspend fun refresh() = lib.refresh(DEV, device)
    }

    private suspend fun TestScope.rig(slots: List<Program?> = listOf(null, null, null)): Rig {
        val sim = BedJetSim(slots = slots)
        val transport = SimTransport(sim, backgroundScope)
        val link = BedJetLink(transport, backgroundScope, now = { currentTime })
        val device =
            BedJetDevice(
                link,
                backgroundScope,
                clock = { LocalTime.of(22, 0) },
                now = { currentTime },
            )
        link.connect()
        val store = InMemoryLibraryStore()
        return Rig(sim, transport, device, store, ProgramLibrary(store, now = { currentTime }))
    }

    private fun cool(fan: Int, hours: Int = 1, minutes: Int = 0) =
        Step(Mode.COOL, fan, 40, hours, minutes)

    private val clockStep = Step(Mode.COOL, 14, 40, 6, 30, clock = true)
    private val a = Program("Alpha", listOf(cool(5), clockStep))
    private val a2 = Program("Alpha", listOf(cool(6), clockStep))
    private val b = Program("Bravo", listOf(cool(9, 2)))
    private val foreign = Program("Official", listOf(cool(12, 3)))

    private suspend fun code(code: ErrorCode, block: suspend () -> Unit) =
        assertEquals(code, assertFailsWith<BedJetException> { block() }.code)

    // ---- encoding ----

    @Test
    fun `steps round-trip through the wire form, clock flag included`() {
        val steps =
            listOf(
                cool(5),
                clockStep,
                Step(Mode.WAIT, 19, 80, 0, 10),
                Step(Mode.HEAT, 0, 66, 11, 59),
            )
        val bytes = encodeSteps(steps)
        assertEquals(20, bytes.size)
        assertContentEquals(byteArrayOf(4, 14, 40, 0x86.toByte(), 30), bytes.copyOfRange(5, 10))
        assertEquals(steps, decodeSteps(bytes))
    }

    @Test
    fun `fingerprint covers sanitized name and every step`() {
        assertEquals(64, a.fingerprint().length)
        assertEquals(a.fingerprint(), a.copy(name = "Alphaé").fingerprint())
        assertNotEquals(a.fingerprint(), a2.fingerprint())
        assertNotEquals(a.fingerprint(), a.copy(name = "Alph").fingerprint())
        assertNotEquals(
            a.fingerprint(),
            a.copy(steps = listOf(cool(5), clockStep.copy(clock = false))).fingerprint(),
        )
    }

    // ---- statuses ----

    @Test
    fun `never read is Unknown, then Empty, NotInLibrary and content matches link`() = runTest {
        val r = rig(listOf(a, null, foreign))
        assertEquals(List(3) { SlotStatus.Unknown }, r.views().map { it.status })
        val alpha = r.lib.create(a)
        r.refresh()
        assertEquals(SlotStatus.InSync(alpha), r.status(0))
        assertEquals(SlotStatus.Empty, r.status(1))
        assertEquals(SlotStatus.NotInLibrary, r.status(2))
    }

    @Test
    fun `content match prefers the most recently edited entry`() = runTest {
        val r = rig(listOf(a, null, null))
        r.lib.create(a)
        advanceTimeBy(1000)
        val newer = r.lib.create(a)
        r.refresh()
        assertEquals(SlotStatus.InSync(newer), r.status(0))
    }

    @Test
    fun `editing an entry turns InSync into Older without a refresh`() = runTest {
        val r = rig(listOf(a, null, null))
        val alpha = r.lib.create(a)
        r.refresh()
        r.store.addRevision(alpha.id, a2, Origin.EDIT, currentTime, makeCurrent = true)
        val s = r.status(0) as SlotStatus.Older
        assertEquals(1, s.slotRev)
        assertEquals(2, s.entry.current.rev)
    }

    @Test
    fun `tombstoned entry shows Deleted, restore brings it back`() = runTest {
        val r = rig(listOf(a, null, null))
        val alpha = r.lib.create(a)
        r.refresh()
        r.lib.delete(alpha.id)
        assertTrue(r.status(0) is SlotStatus.Deleted)
        r.lib.restore(alpha.id)
        assertEquals(SlotStatus.InSync(alpha), r.status(0))
    }

    @Test
    fun `slot of a deleted entry overwritten elsewhere is not in the library`() = runTest {
        val r = rig(listOf(a, null, null))
        val alpha = r.lib.create(a)
        r.refresh()
        r.lib.delete(alpha.id)
        r.sim.slots[0] = b
        r.refresh()
        assertEquals(SlotStatus.NotInLibrary, r.status(0))
        assertEquals(1, r.lib.revisions(alpha.id).size)
    }

    @Test
    fun `outside change is saved once as a non-current revision`() = runTest {
        val r = rig(listOf(a, null, null))
        val alpha = r.lib.create(a)
        r.refresh()
        r.sim.slots[0] = foreign
        repeat(3) { r.refresh() }
        val revs = r.lib.revisions(alpha.id)
        assertEquals(listOf(2, 1), revs.map { it.rev })
        assertEquals(Origin.OUTSIDE, revs[0].origin)
        assertEquals(1, r.store.entry(alpha.id)!!.current.rev)
        assertEquals(SlotStatus.ChangedOutside(r.store.entry(alpha.id)!!, 2), r.status(0))
    }

    @Test
    fun `a slot now holding another entry re-links without a revision`() = runTest {
        val r = rig(listOf(a, null, null))
        val alpha = r.lib.create(a)
        val bravo = r.lib.create(b)
        r.refresh()
        r.sim.slots[0] = b
        r.refresh()
        assertEquals(SlotStatus.InSync(bravo), r.status(0))
        assertEquals(1, r.lib.revisions(alpha.id).size)
    }

    // ---- resolutions ----

    private suspend fun TestScope.changedOutside(): Pair<Rig, LibraryEntry> {
        val r = rig(listOf(a, null, null))
        val alpha = r.lib.create(a)
        r.refresh()
        r.sim.slots[0] = foreign
        r.refresh()
        return r to alpha
    }

    @Test
    fun `take theirs makes the outside revision current`() = runTest {
        val (r, alpha) = changedOutside()
        r.lib.resolve(DEV, 0, Resolution.TAKE_THEIRS)
        val entry = r.store.entry(alpha.id)!!
        assertEquals(2, entry.current.rev)
        assertEquals(SlotStatus.InSync(entry), r.status(0))
        r.refresh()
        assertEquals(SlotStatus.InSync(entry), r.status(0))
    }

    @Test
    fun `keep mine shows the slot as holding an older revision`() = runTest {
        val (r, alpha) = changedOutside()
        r.lib.resolve(DEV, 0, Resolution.KEEP_MINE)
        val entry = r.store.entry(alpha.id)!!
        assertEquals(1, entry.current.rev)
        assertEquals(SlotStatus.Older(entry, 2), r.status(0))
        r.refresh()
        assertEquals(SlotStatus.Older(entry, 2), r.status(0))
    }

    @Test
    fun `split off creates a new entry linked to the slot`() = runTest {
        val (r, alpha) = changedOutside()
        r.lib.resolve(DEV, 0, Resolution.SPLIT_OFF)
        val split = (r.status(0) as SlotStatus.InSync).entry
        assertNotEquals(alpha.id, split.id)
        assertEquals(Origin.OUTSIDE, split.current.origin)
        assertEquals(foreign, split.current.program)
        assertEquals(1, r.store.entry(alpha.id)!!.current.rev)
    }

    @Test
    fun `keep saves an unknown slot and links it`() = runTest {
        val r = rig(listOf(foreign, null, null))
        r.refresh()
        val kept = r.lib.keep(DEV, 0)
        assertEquals(Origin.KEPT_FROM_SLOT, kept.current.origin)
        assertEquals(SlotStatus.InSync(kept), r.status(0))
    }

    // ---- push ----

    @Test
    fun `push writes, verifies and records`() = runTest {
        val r = rig()
        val alpha = r.lib.create(a)
        r.refresh()
        r.lib.push(DEV, r.device, 1, alpha.id)
        assertEquals(a, r.sim.slots[1])
        val state = r.views()[1].state
        assertEquals(PushRecord(alpha.id, 1, a.fingerprint(), state.pushed!!.at), state.pushed)
        assertNull(state.pending)
        assertEquals(SlotStatus.InSync(alpha), r.status(1))
    }

    @Test
    fun `push saves an orphan it overwrites first`() = runTest {
        val r = rig(listOf(foreign, null, null))
        val alpha = r.lib.create(a)
        r.refresh()
        r.lib.push(DEV, r.device, 0, alpha.id)
        val saved = r.lib.entries().first().single { it.id != alpha.id }
        assertEquals(Origin.KEPT_FROM_SLOT, saved.current.origin)
        assertEquals(foreign, saved.current.program)
    }

    @Test
    fun `push over a known program saves nothing`() = runTest {
        val r = rig(listOf(b, null, null))
        val alpha = r.lib.create(a)
        r.lib.create(b)
        r.refresh()
        r.lib.push(DEV, r.device, 0, alpha.id)
        assertEquals(2, r.lib.entries().first().size)
    }

    @Test
    fun `push refuses while a program runs`() = runTest {
        val r = rig(listOf(b, null, null))
        val alpha = r.lib.create(a)
        r.refresh()
        r.device.startProgram(0)
        val e = assertFailsWith<BedJetException> { r.lib.push(DEV, r.device, 1, alpha.id) }
        assertEquals(ErrorCode.INVALID_PROGRAM, e.code)
        assertTrue("Stop the program" in e.message!!)
        assertNull(r.sim.slots[1])
    }

    @Test
    fun `push detects a slot changed since the cache and reconciles it`() = runTest {
        val r = rig(listOf(a, null, null))
        val alpha = r.lib.create(a)
        val bravo = r.lib.create(b)
        r.refresh()
        r.sim.slots[0] = foreign
        code(ErrorCode.SLOT_CHANGED) { r.lib.push(DEV, r.device, 0, bravo.id) }
        assertEquals(foreign, r.sim.slots[0])
        assertTrue(r.status(0) is SlotStatus.ChangedOutside)
        assertEquals(2, r.lib.revisions(alpha.id).size)
    }

    @Test
    fun `interrupted write shows Interrupted until the unit holds the target`() = runTest {
        val r = rig()
        val alpha = r.lib.create(a)
        r.refresh()
        // Fragments land, the link drops on the name frame.
        r.transport.inject(Fault.LoseLink) {
            it is Op.Write && it.bytes.size == 19 && it.bytes[0] == 0x40.toByte()
        }
        assertFailsWith<BedJetException> { r.lib.push(DEV, r.device, 2, alpha.id) }
        advanceTimeBy(5_000)
        r.refresh()
        assertEquals(SlotStatus.Interrupted(r.views()[2].state.pending!!), r.status(2))
        assertEquals(alpha.id, (r.status(2) as SlotStatus.Interrupted).target.programId)
        r.sim.slots[2] = a
        r.refresh()
        assertEquals(SlotStatus.InSync(alpha), r.status(2))
        assertNull(r.views()[2].state.pending)
    }

    @Test
    fun `run pushes only when needed, then starts`() = runTest {
        val r = rig()
        val alpha = r.lib.create(a)
        r.refresh()
        r.lib.run(DEV, r.device, 0, alpha.id)
        assertTrue(r.device.status!!.status.extra!!.programStep > 0)
        assertEquals(StartedSlot(DEV, 0), r.lib.started.value)
        r.device.stopProgram()
        r.device.waitForStatus { it.extraFresh && it.status.extra!!.programStep == 0 }
        val writes = r.transport.writes.size
        r.lib.run(DEV, r.device, 0, alpha.id)
        val newFrames = r.transport.writes.drop(writes).filter { it.startsWith("40") }
        assertEquals(emptyList(), newFrames)
    }

    // ---- ranking ----

    @Test
    fun `rankSlots orders current, older, empty, oldest push, rest`() = runTest {
        val r = rig()
        val alpha = r.lib.create(a)
        val bravo = r.lib.create(b)
        val other = r.lib.create(foreign)
        r.refresh()
        r.lib.push(DEV, r.device, 2, bravo.id)
        advanceTimeBy(1000)
        r.lib.push(DEV, r.device, 1, other.id)
        assertEquals(listOf(0, 2, 1), r.lib.rankSlots(DEV, alpha.id))
        r.lib.push(DEV, r.device, 0, alpha.id)
        assertEquals(listOf(0, 2, 1), r.lib.rankSlots(DEV, alpha.id))
        r.store.addRevision(alpha.id, a2, Origin.EDIT, currentTime, makeCurrent = true)
        r.lib.push(DEV, r.device, 1, alpha.id)
        // 1 holds current, 0 holds rev 1, 2 is in sync with Bravo.
        assertEquals(listOf(1, 0, 2), r.lib.rankSlots(DEV, alpha.id))
        r.sim.slots[2] = Program("Mystery", listOf(cool(3)))
        r.refresh()
        assertTrue(r.status(2) is SlotStatus.ChangedOutside)
        assertEquals(listOf(1, 0, 2), r.lib.rankSlots(DEV, alpha.id))
    }

    // ---- link fairness ----

    @Test
    fun `a user command during refresh waits behind one query, not the whole refresh`() = runTest {
        val r = rig(listOf(a, b, foreign))
        r.transport.latencyMs = 100
        val refresh = async { r.refresh() }
        advanceTimeBy(250)
        val mark = r.transport.ops.size
        val inFlight =
            r.transport.ops
                .filterIsInstance<Op.Write>()
                .last { it.bytes[0] == 0x41.toByte() }
                .bytes
                .toList()
        var fanDone = false
        launch {
            r.device.setFan(11)
            fanDone = true
        }
        while (!fanDone) advanceTimeBy(10)
        assertFalse(refresh.isCompleted, "refresh finished before the command")
        val before =
            r.transport.ops.drop(mark).takeWhile {
                !(it is Op.Write && it.bytes.contentEquals(Command.setFan(11)))
            }
        val otherQueries =
            before.filterIsInstance<Op.Write>().filter {
                it.bytes[0] == 0x41.toByte() && it.bytes.toList() != inFlight
            }
        assertEquals(emptyList(), otherQueries.map { it.bytes.toHex() })
        refresh.await()
        runCurrent()
        assertEquals(11, r.sim.fan)
    }

    private companion object {
        const val DEV = "demo"
    }
}
