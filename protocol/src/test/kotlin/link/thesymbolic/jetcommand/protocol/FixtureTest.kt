package link.thesymbolic.jetcommand.protocol

import kotlin.test.Test
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Replays what the web app's codec and sim recorded (scripts/gen-fixtures.mjs). */
class FixtureTest {
    private val fixtures =
        Json.parseToJsonElement(checkNotNull(javaClass.getResource("/fixtures.json")).readText())
            .jsonObject

    private fun cases(key: String) = fixtures.getValue(key).jsonArray.map { it }

    private val JsonElement.num: Int
        get() = jsonPrimitive.int

    private val JsonElement.str: String
        get() = jsonPrimitive.content

    private fun step(json: JsonElement) =
        json.jsonObject.let {
            val hours = it.getValue("hours").num
            val minutes = it.getValue("minutes").num
            Step(
                mode = checkNotNull(Mode.of(it.getValue("mode").num)),
                fan = it.getValue("fan").num,
                temp = it.getValue("temp").num,
                timing =
                    if (it.getValue("clock").jsonPrimitive.boolean) Timing.Until(hours, minutes)
                    else Timing.For(hours, minutes),
            )
        }

    private fun stepJson(s: Step) = buildJsonObject {
        put("mode", s.mode.ordinal)
        put("fan", s.fan)
        put("temp", s.temp)
        when (val t = s.timing) {
            is Timing.For -> {
                put("hours", t.hours)
                put("minutes", t.minutes)
                put("clock", false)
            }
            is Timing.Until -> {
                put("hours", t.hour)
                put("minutes", t.minute)
                put("clock", true)
            }
        }
    }

    /** The JS codec refuses a bad slot number with a RangeError. */
    private fun slotArg(n: Int) =
        Slot.of(n) ?: throw IllegalArgumentException("Program slot must be 0–2, got $n")

    private fun statusJson(s: Status?): JsonElement =
        if (s == null) JsonNull
        else
            buildJsonObject {
                put("partial", s.partial)
                put("length", s.length)
                put("remainingSeconds", s.remainingSeconds)
                put("actualTemp", s.actualTemp)
                put("targetTemp", s.targetTemp)
                put("mode", s.mode.ordinal)
                put("fanStep", s.fanStep)
                put("fanPercent", s.fanPercent)
                put("maxRuntimeMinutes", s.maxRuntimeMinutes)
                put("minTemp", s.minTemp)
                put("maxTemp", s.maxTemp)
                put("turboTime", JsonArray(s.turboTime.map(::JsonPrimitive)))
                put("ambientTemp", s.ambientTemp)
                put("shutdownReason", s.shutdownReason)
                put("dualZone", s.dualZone)
                put("updatePhase", s.updatePhase)
                put(
                    "extra",
                    s.extra?.let { e ->
                        buildJsonObject {
                            put("flags", e.flags)
                            put("connTestPassed", e.connTestPassed)
                            put("ledEnabled", e.ledEnabled)
                            put("unitsSetUp", e.unitsSetUp)
                            put("beepsMuted", e.beepsMuted)
                            put("programStep", e.programStep)
                            put("notification", e.notification.code)
                        }
                    } ?: JsonNull,
                )
            }

    /**
     * JS error names: BedJetError codes are camelCase ErrorCode names; RangeError is a refused
     * argument.
     */
    private fun errorName(e: Throwable) =
        when (e) {
            is BedJetException ->
                e.code.name.lowercase().replace(Regex("_(.)")) { it.groupValues[1].uppercase() }
            is IllegalArgumentException -> "RangeError"
            else -> throw e
        }

    private inline fun outcome(block: () -> JsonElement): JsonObject =
        try {
            JsonObject(mapOf("out" to block()))
        } catch (e: Exception) {
            JsonObject(mapOf("error" to JsonPrimitive(errorName(e))))
        }

    private fun expected(case: JsonObject) =
        JsonObject(case.filterKeys { it == "out" || it == "error" })

    private fun hexList(frames: List<ByteArray>) =
        JsonArray(frames.map { JsonPrimitive(it.toHex()) })

    private fun check(name: String, mismatches: List<String>) {
        if (mismatches.isNotEmpty()) {
            fail("$name: ${mismatches.size} mismatches\n" + mismatches.take(15).joinToString("\n"))
        }
    }

    @Test
    fun encoders() =
        check(
            "encoders",
            cases("encoders")
                .map { it.jsonObject }
                .mapNotNull { case ->
                    val args = case.getValue("args").jsonArray
                    val actual = outcome { encode(case.getValue("fn").str, args) }
                    if (actual != expected(case))
                        "${case["fn"]}$args: expected ${expected(case)}, got $actual"
                    else null
                },
        )

    private val oneArgEncoders: Map<String, (Int) -> ByteArray> =
        mapOf(
            "button" to Command::button,
            "setRuntime" to Command::setRuntime,
            "setTemperature" to Command::setTemperature,
            "setFan" to Command::setFan,
            "recallMemory" to { Command.recallMemory(typed(MemorySlot.of(it))) },
            "saveMemory" to { Command.saveMemory(typed(MemorySlot.of(it))) },
            "startProgram" to { Command.startProgram(slotArg(it)) },
            "deleteProgram" to { Command.deleteProgram(slotArg(it)) },
        )

    // The web app range-checks plain numbers; here the type does, so an untypeable one is its
    // RangeError.
    private fun <T : Any> typed(value: T?): T = requireNotNull(value)

    private fun encode(fn: String, args: JsonArray) =
        when (fn) {
            in oneArgEncoders -> hexList(listOf(oneArgEncoders.getValue(fn)(args[0].num)))
            "setMode" ->
                hexList(
                    listOf(
                        Command.setMode(requireNotNull(Mode.of(args[0].num)?.let(SettableMode::of)))
                    )
                )
            "setClock" -> hexList(listOf(Command.setClock(args[0].num, args[1].num)))
            "query" -> hexList(listOf(Command.query(args[0].num, args[1].num)))
            "nameFrame" ->
                hexList(listOf(Command.nameFrame(typed(NameTarget.of(args[0].num)), args[1].str)))
            "writeProgram" ->
                hexList(Command.writeProgram(slotArg(args[0].num), args[1].jsonArray.map(::step)))
            else -> error("unknown encoder $fn")
        }

    @Test
    fun parsers() =
        check(
            "parsers",
            cases("parsers")
                .map { it.jsonObject }
                .mapNotNull { case ->
                    val args = case.getValue("args").jsonArray
                    val bytes = hexToBytes(args[0].str)
                    val actual: JsonElement =
                        when (val fn = case.getValue("fn").str) {
                            "parseStatus" -> statusJson(Reply.parseStatus(bytes))
                            "parseFragment" ->
                                Reply.parseFragment(bytes, args[1].num)?.let {
                                    buildJsonObject {
                                        put("total", it.total)
                                        put("steps", JsonArray(it.steps.map(::stepJson)))
                                    }
                                } ?: JsonNull
                            "parseText" -> JsonPrimitive(Reply.parseText(bytes))
                            "parseNames" -> JsonArray(Reply.parseNames(bytes).map(::JsonPrimitive))
                            else -> error("unknown parser $fn")
                        }
                    val want = case.getValue("out")
                    if (actual != want) "${case["fn"]}$args: expected $want, got $actual" else null
                },
        )

    @Test
    fun heatHours() =
        check(
            "heatHours",
            cases("heatHours").mapNotNull { row ->
                val (mode, temp, fan) = row.jsonArray.map { it.num }
                val want = row.jsonArray[3].num
                val got = heatHours(checkNotNull(Mode.of(mode)), temp, fan)
                if (got != want) "heatHours($mode, $temp, $fan): expected $want, got $got" else null
            },
        )

    @Test
    fun validation() =
        check(
            "validation",
            cases("validations")
                .map { it.jsonObject }
                .mapNotNull { case ->
                    val steps = case.getValue("steps").jsonArray.map(::step)
                    val got =
                        JsonArray(
                            validateProgram(steps, case.getValue("now").num).map {
                                buildJsonObject {
                                    put("index", it.index)
                                    put("code", errorName(BedJetException(it.code)))
                                    put("message", it.message)
                                }
                            }
                        )
                    val want =
                        firmwareVerdict(
                            case.getValue("out").jsonArray,
                            steps,
                            case.getValue("now").num,
                        )
                    if (got != want)
                        "validateProgram(now=${case["now"]}, $steps):\n  expected $want\n  got      $got"
                    else null
                },
        )

    /**
     * The web app predates the firmware reading (`FUN_400ece98`): it refuses a clock-time step 0 or
     * exactly 12 h after the previous end and a 12:00 duration step, which the unit runs. Those
     * entries are dropped from its verdict; StepRulesTest pins them.
     */
    private fun firmwareVerdict(web: JsonArray, steps: List<Step>, now: Int): JsonArray {
        val boundary = mutableSetOf<Int>()
        var end = now % 1440
        steps.forEachIndexed { i, step ->
            if (stepProblem(step) != null) return@forEachIndexed
            val minutes =
                when (val t = step.timing) {
                    is Timing.Until -> Math.floorMod(t.minuteOfDay - end, 1440)
                    is Timing.For -> t.totalMinutes
                }
            val atBoundary =
                if (step.timing is Timing.Until) minutes == 0 || minutes == 720
                else minutes == 720 && longestStepMinutes(step.mode, step.temp, step.fan) == 720
            if (atBoundary) boundary += i
            end = (end + minutes) % 1440
        }
        return JsonArray(
            web.filterNot {
                    val e = it.jsonObject
                    e.getValue("index").num in boundary && e.getValue("code").str == "stepTooLong"
                }
                .map {
                    val e = it.jsonObject
                    if (e.getValue("message").str != "A step must be shorter than 12 h.") e
                    else JsonObject(e + ("message" to JsonPrimitive("A step runs at most 12 h.")))
                }
        )
    }

    @Test
    fun formatTemp() =
        check(
            "formatTemp",
            cases("formatTemp").mapNotNull { row ->
                val (halfC, unit, want) = row.jsonArray
                val got = formatTemp(halfC.num, TempUnit.valueOf(unit.str))
                if (got != want.str) "formatTemp($halfC, $unit): expected $want, got $got" else null
            },
        )

    @Test
    fun simScenarios() =
        check(
            "sim",
            cases("scenarios")
                .map { it.jsonObject }
                .mapNotNull { scenario ->
                    val sim = BedJetSim()
                    scenario.getValue("trace").jsonArray.withIndex().firstNotNullOfOrNull {
                        (i, entry) ->
                        val case = entry.jsonObject
                        val op = case.getValue("op").jsonObject
                        if (startsUnlikeWebApp(sim, op)) return@mapNotNull null
                        val actual = runOp(sim, op)
                        val packet = sim.packet().toHex()
                        when {
                            actual != null && actual != expected(case) ->
                                "${scenario["name"]} #$i $op: expected ${expected(case)}, got $actual"
                            packet != case.getValue("packet").str ->
                                "${scenario["name"]} #$i $op: packet\n  expected ${case["packet"]}\n  got      \"$packet\""
                            else -> null
                        }
                    }
                },
        )

    /**
     * The web sim predates the firmware reading (`FUN_400ece98`, `FUN_400e65bc`): it needs the
     * clock for any program, refuses long duration steps, and times clock-time steps from the live
     * clock. A scenario is replayed only up to the first start where that could matter;
     * BedJetSimTest pins the firmware behaviour.
     */
    private fun startsUnlikeWebApp(sim: BedJetSim, op: JsonObject): Boolean =
        op["send"]?.jsonArray.orEmpty().any { frame ->
            val b = hexToBytes(frame.str)
            val slot =
                if (b.size >= 2 && b[0].toInt() == 1)
                    Button.slotOfStartProgram(b[1].toInt() and 0xff)
                else null
            val steps = slot?.let { sim.slots[it.index]?.steps } ?: return@any false
            val clock = sim.clock ?: return@any steps.none { it.timing is Timing.Until }
            val plan = planProgram(steps, clock / 60)
            val clockSteps = steps.indices.filter { steps[it].timing is Timing.Until }
            plan.any { it.refused || it.cut } ||
                clockSteps.any { plan[it].wanted == 0 || plan[it].wanted == 720 } ||
                (clockSteps.isNotEmpty() && steps.any { stepProblem(it) != null })
        }

    /** The op's outcome, or null for ops that only move the sim along. */
    private fun runOp(sim: BedJetSim, op: JsonObject): JsonObject? =
        when {
            "send" in op -> {
                var result = JsonObject(mapOf("out" to JsonNull))
                for (frame in op.getValue("send").jsonArray) {
                    result =
                        outcome {
                            sim.send(hexToBytes(frame.str))
                            JsonNull
                        }
                            .takeIf { "error" in it } ?: result
                }
                result
            }
            "query" in op ->
                outcome {
                    JsonPrimitive(sim.query(op.getValue("query").num).toHex())
                }
            "tick" in op -> null.also { repeat(op.getValue("tick").num) { sim.tick() } }
            "skip" in op ->
                null.also {
                    sim.skipAhead(op.getValue("skip").jsonPrimitive.double)
                }
            "cutOut" in op -> null.also { sim.cutOut(op.getValue("cutOut").num) }
            else -> error("unknown op $op")
        }
}
