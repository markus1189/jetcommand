package link.thesymbolic.jetcommand.protocol

/**
 * The official app's suggested programs (BEDJET_PROTOCOL.md, "Suggested programs"), seeded into an empty library.
 * Names are ASCII because the unit stores nothing else.
 */
object ProgramTemplates {
    private fun turbo(fan: Int, minutes: Int) = Step(Mode.TURBO, fanStepFromPercent(fan), 86, 0, minutes)
    private val wait = Step(Mode.WAIT, 19, 80, 0, 10)
    private fun cool(fan: Int, minutes: Int) = Step(Mode.COOL, fanStepFromPercent(fan), 40, minutes / 60, minutes % 60)
    private fun heat(fan: Int, temp: Int, minutes: Int) = Step(Mode.HEAT, fanStepFromPercent(fan), temp, minutes / 60, minutes % 60)

    val all = listOf(
        Program("Cold 15-25", listOf(turbo(100, 5), turbo(50, 5), wait, cool(30, 10), heat(30, 47, 390))),
        Program("Cold 26+", listOf(turbo(100, 5), turbo(50, 3), wait, cool(30, 10), heat(30, 49, 360))),
        Program("Neutral 15-25", listOf(turbo(100, 5), turbo(50, 4), wait, cool(40, 20), cool(25, 390))),
        Program("Neutral 26+", listOf(turbo(100, 5), turbo(50, 3), wait, cool(30, 20), cool(20, 360))),
        Program("Hot 15-25", listOf(turbo(100, 3), turbo(30, 3), wait, cool(50, 20), cool(35, 390))),
        Program("Hot 26+", listOf(turbo(100, 3), turbo(30, 3), wait, cool(45, 20), cool(30, 360))),
    )
}
