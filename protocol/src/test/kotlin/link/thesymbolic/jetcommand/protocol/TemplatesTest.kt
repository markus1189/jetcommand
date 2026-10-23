package link.thesymbolic.jetcommand.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

class TemplatesTest {
    @Test
    fun templatesAreStorableAsIs() {
        for (p in ProgramTemplates.all) {
            assertEquals(p.name, Command.sanitizeName(p.name), p.name)
            assertEquals(emptyList(), validateProgram(p.steps, nowMinutes = 22 * 60), p.name)
            assertEquals(p.steps, decodeSteps(encodeSteps(p.steps)), p.name)
        }
    }
}
