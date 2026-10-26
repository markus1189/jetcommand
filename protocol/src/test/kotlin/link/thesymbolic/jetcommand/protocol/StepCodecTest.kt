package link.thesymbolic.jetcommand.protocol

import io.kotest.property.Arb
import io.kotest.property.Exhaustive
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import io.kotest.property.exhaustive.ints
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class StepCodecTest {
    // Every value the wire can carry: hours have 7 bits, minutes a whole byte, fan is clamped.
    private val timing =
        Arb.choice(
            Arb.bind(Arb.int(0..127), Arb.int(0..255), Timing::For),
            Arb.bind(Arb.int(0..127), Arb.int(0..255), Timing::Until),
        )
    private val step = Arb.bind(Arb.enum<Mode>(), Arb.int(0..19), Arb.int(0..255), timing, ::Step)

    private fun bytes(range: IntRange = 0..255) =
        Arb.list(Arb.int(range), 5..5).map { b -> ByteArray(5) { b[it].toByte() } }

    @Test
    fun `decode inverts encode for every wire-representable step`() = runTest {
        checkAll(step) { s -> assertEquals(s, decodeStep(encodeStep(s))) }
    }

    @Test
    fun `encode inverts decode for every step with a known mode and fan up to 19`() = runTest {
        checkAll(bytes()) { b ->
            b[0] = (b[0].toInt() and 0xff).mod(Mode.entries.size).toByte()
            b[1] = (b[1].toInt() and 0xff).mod(20).toByte()
            assertContentEquals(b, encodeStep(assertNotNull(decodeStep(b))))
        }
    }

    @Test
    fun `decode is total, null only for an unknown mode, and fan above 19 encodes as 19`() =
        runTest {
            checkAll(bytes()) { b ->
                val s = decodeStep(b)
                val mode = b[0].toInt() and 0xff
                if (mode >= Mode.entries.size) assertNull(s)
                else {
                    val back = encodeStep(assertNotNull(s))
                    assertEquals(minOf(19, b[1].toInt() and 0xff), back[1].toInt())
                }
            }
        }

    @Test
    fun `decodeSteps inverts encodeSteps`() = runTest {
        checkAll(Arb.list(step, 0..Limits.STEPS_PER_PROGRAM)) { steps ->
            assertEquals(steps, decodeSteps(encodeSteps(steps)))
        }
    }

    // Computed on 93c22c4, before Step.clock became Timing; the library stores these hashes.
    @Test
    fun `fingerprint of a known program is pinned`() {
        val p =
            Program(
                "Cool night ü",
                listOf(
                    Step(Mode.COOL, fan = 9, temp = 44, Timing.For(1, 30)),
                    Step(Mode.HEAT, fan = 12, temp = 70, Timing.For(0, 45)),
                    Step(Mode.WAIT, fan = 19, temp = 80, Timing.Until(6, 15)),
                ),
            )
        assertEquals("04 09 2c 01 1e 01 0c 46 00 2d 06 13 50 86 0f", encodeSteps(p.steps).toHex())
        assertEquals(
            "23b597cd695d2f15244d9ea9c4c31c476ca3c61b9f7e9d12f277e2d1d6d7e6bd",
            p.fingerprint(),
        )
    }

    @Test
    fun `Slot of accepts exactly 0 to 2`() = runTest {
        checkAll(Arb.int()) { i ->
            val s = Slot.of(i)
            assertEquals(i in 0..2, s != null, "$i")
            if (s != null) assertEquals(i, s.index)
        }
        assertEquals(listOf(0, 1, 2), Slot.all.map { it.index })
    }

    @Test
    fun `slot buttons round-trip and never collide`() = runTest {
        checkAll(Exhaustive.ints(0..255)) { code ->
            val start = Button.slotOfStartProgram(code)
            val delete = Button.slotOfDeleteProgram(code)
            assertTrue(start == null || delete == null, "0x${code.toString(16)}")
            start?.let { assertEquals(code, Button.startProgram(it)) }
            delete?.let { assertEquals(code, Button.deleteProgram(it)) }
            if (start != null || delete != null) assertTrue(code in Button.ALLOWED)
        }
        for (s in Slot.all) {
            assertEquals(s, Button.slotOfStartProgram(Button.startProgram(s)))
            assertEquals(s, Button.slotOfDeleteProgram(Button.deleteProgram(s)))
        }
    }

    @Test
    fun `fragment and name ids round-trip through their slot`() = runTest {
        checkAll(Exhaustive.ints(0..255)) { id ->
            Query.fragmentOf(id)?.let { (s, i) -> assertEquals(id, Query.fragment(s, i)) }
            NameTarget.of(id)?.let { assertEquals(id, it.param) }
        }
        for (s in Slot.all) {
            assertEquals(NameTarget.ProgramName(s), NameTarget.of(NameTarget.ProgramName(s).param))
            repeat(Limits.FRAGMENTS_PER_SLOT) { i ->
                assertEquals(s to i, Query.fragmentOf(Query.fragment(s, i)))
            }
        }
    }
}
