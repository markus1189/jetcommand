// Port of the web app's `bedjet-codec` script; fixtures.json replays its recorded behaviour against
// this.
package link.thesymbolic.jetcommand.protocol

import kotlin.math.roundToLong

object Uuids {
    private fun uuid(short: String) = "0000$short-bed0-0080-aa55-4265644a6574"

    val SERVICE = uuid("1000")
    val STATUS = uuid("2000")
    val NAME = uuid("2001")
    val COMMAND = uuid("2004")
    val REPLY = uuid("2005")
    val REPLY_ALT = uuid("2006")
}

/** Ordinals are the wire values. */
enum class Mode(val label: String) {
    STANDBY("Off"),
    HEAT("Heat"),
    TURBO("Turbo"),
    EXT_HEAT("Ext. heat"),
    COOL("Cool"),
    DRY("Dry"),
    WAIT("Wait");

    val usesTemperature
        get() = this != STANDBY && this != WAIT && this != TURBO

    val clockTimeAllowed
        get() = this == WAIT || this == EXT_HEAT || this == COOL

    companion object {
        fun of(code: Int): Mode? = entries.getOrNull(code)
    }
}

object Button {
    const val OFF = 0x01
    const val COOL = 0x02
    const val HEAT = 0x03
    const val TURBO = 0x04
    const val DRY = 0x05
    const val EXT_HEAT = 0x06
    const val FAN_UP = 0x10
    const val FAN_DOWN = 0x11
    const val TEMP_UP_C = 0x12
    const val TEMP_DOWN_C = 0x13
    const val TEMP_UP_F = 0x14
    const val TEMP_DOWN_F = 0x15
    const val MEMORY_1 = 0x20
    const val MEMORY_3 = 0x22
    const val SAVE_MEMORY_1 = 0x28
    const val SAVE_MEMORY_3 = 0x2a
    const val LED_ON = 0x46
    const val LED_OFF = 0x47
    const val MUTE = 0x48
    const val UNMUTE = 0x49
    const val ACK = 0x52
    const val START_PROGRAM = 0x80
    const val DELETE_PROGRAM = 0x83

    // A whitelist rather than a blacklist: 0x4C (factory reset) and any other unknown code can
    // never be encoded.
    val ALLOWED =
        setOf(
            OFF,
            COOL,
            HEAT,
            TURBO,
            DRY,
            EXT_HEAT,
            FAN_UP,
            FAN_DOWN,
            TEMP_UP_C,
            TEMP_DOWN_C,
            TEMP_UP_F,
            TEMP_DOWN_F,
            MEMORY_1,
            MEMORY_1 + 1,
            MEMORY_3,
            SAVE_MEMORY_1,
            SAVE_MEMORY_1 + 1,
            SAVE_MEMORY_3,
            LED_ON,
            LED_OFF,
            MUTE,
            UNMUTE,
            ACK,
            START_PROGRAM,
            0x81,
            0x82,
            DELETE_PROGRAM,
            0x84,
            0x85,
        )

    fun forMode(mode: Mode): Int? =
        when (mode) {
            Mode.STANDBY -> OFF
            Mode.HEAT -> HEAT
            Mode.TURBO -> TURBO
            Mode.EXT_HEAT -> EXT_HEAT
            Mode.COOL -> COOL
            Mode.DRY -> DRY
            Mode.WAIT -> null
        }
}

object Notification {
    const val NONE = 0
    const val CLEAN_FILTER = 1
    const val UPDATE_AVAILABLE = 2
    const val UPDATE_FAILED = 3
    const val PROGRAM_NEEDS_CLOCK = 4
    const val PROGRAM_TOO_LONG = 5

    // 4 and 5 are the official app's wording (MainFragment.java:370-386).
    val TEXT =
        mapOf(
            CLEAN_FILTER to "Time to clean the filter.",
            UPDATE_AVAILABLE to "A firmware update is available.",
            UPDATE_FAILED to "The firmware update failed.",
            PROGRAM_NEEDS_CLOCK to
                "The specified sequence cannot be run because the clock is not set.",
            PROGRAM_TOO_LONG to
                "The specified sequence cannot be run because it contains steps that would be too long running from the current time.",
        )
}

object Query {
    const val DEVICE_NAME = 0x00
    const val MEMORY_NAMES = 0x01
    const val PROGRAM_NAMES = 0x04
    const val FIRMWARE = 0x20

    fun fragment(slot: Int, index: Int) = 7 + 4 * slot + index
}

object NameParam {
    const val DEVICE = 0

    fun memory(n: Int) = n

    fun program(slot: Int) = 4 + slot
}

object Limits {
    const val SLOTS = 3
    const val STEPS_PER_PROGRAM = 32
    const val STEPS_PER_FRAGMENT = 8
    const val FRAGMENTS_PER_SLOT = 4
    const val NAME_LENGTH = 15
    const val MAX_STEP_MINUTES = 11 * 60 + 59
    const val TURBO_MAX_MINUTES = 10
}

data class TempRange(val min: Int, val max: Int)

// OFF Utils.getMinMaxTemp; used when status bytes 13/14 are missing or belong to another mode.
val TEMP_RANGES =
    mapOf(
        Mode.STANDBY to TempRange(80, 80),
        Mode.HEAT to TempRange(45, 80),
        Mode.TURBO to TempRange(86, 86),
        Mode.EXT_HEAT to TempRange(38, 67),
        Mode.COOL to TempRange(38, 62),
        Mode.DRY to TempRange(40, 62),
        Mode.WAIT to TempRange(80, 80),
    )

private val QUIET = intArrayOf(0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0xc6, 0x66, 0x66, 0x41)
private val STRONG = intArrayOf(0xcc, 0xcc, 0xcc, 0xcc, 0x44, 0x44, 0x44, 0x42, 0x21, 0x11, 0x11)
private val MID = intArrayOf(0xcc, 0xcc, 0xcc, 0xcc, 0xc4, 0x44, 0x44, 0x42, 0x21, 0x11, 0x11)

// OFF Constants.runtime_table: rows = fan step, columns = target from 60 half-°C in pairs of
// half-degrees,
// high nibble for the even offset, low nibble for the odd one.
private val HEAT_TABLE =
    listOf(
        QUIET,
        QUIET,
        QUIET,
        QUIET,
        QUIET,
        intArrayOf(0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0xc6, 0x66, 0x64, 0x44, 0x21),
        intArrayOf(0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0xc6, 0x64, 0x44, 0x44, 0x21),
        intArrayOf(0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0xc6, 0x64, 0x42, 0x22, 0x21),
        intArrayOf(0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0xc4, 0x44, 0x42, 0x22, 0x21),
        intArrayOf(0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0xc4, 0x44, 0x42, 0x11, 0x11),
        intArrayOf(0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0xc4, 0x44, 0x44, 0x42, 0x11, 0x11),
        intArrayOf(0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0x44, 0x44, 0x42, 0x21, 0x11, 0x11),
        MID,
        MID,
        STRONG,
        STRONG,
        STRONG,
        STRONG,
        STRONG,
        STRONG,
    )

enum class ErrorCode(val text: String) {
    NOT_CONNECTED("Couldn't reach your BedJet. Check that it's plugged in and nearby."),
    TIMEOUT("Your BedJet didn't respond. Move closer and try again."),
    BAD_RESPONSE("Your BedJet sent something unexpected. Try again."),
    VERIFY_FAILED("The program didn't save correctly. Try again."),
    CLOCK_NOT_SET(Notification.TEXT.getValue(Notification.PROGRAM_NEEDS_CLOCK)),
    STEP_TOO_LONG(Notification.TEXT.getValue(Notification.PROGRAM_TOO_LONG)),
    INVALID_PROGRAM("This program can't be stored on the BedJet."),
    SLOT_CHANGED("This program slot changed since it was last read. Check it and try again."),
}

class BedJetException(val code: ErrorCode, message: String = code.text, cause: Throwable? = null) :
    Exception(message, cause)

data class Step(
    val mode: Mode,
    val fan: Int,
    val temp: Int,
    val hours: Int,
    val minutes: Int,
    val clock: Boolean = false,
) {
    val totalMinutes
        get() = hours * 60 + minutes

    companion object {
        fun default() = Step(Mode.WAIT, fan = 19, temp = 80, hours = 1, minutes = 0)
    }
}

data class Fragment(val total: Int, val steps: List<Step>)

data class StatusExtra(
    val flags: Int,
    val connTestPassed: Boolean,
    val ledEnabled: Boolean,
    val unitsSetUp: Boolean,
    val beepsMuted: Boolean,
    val programStep: Int,
    val notification: Int,
)

data class Status(
    /** The first notification of a status split across two; byte 0 is 1. */
    val partial: Boolean,
    val length: Int,
    val remainingSeconds: Int,
    val actualTemp: Int,
    val targetTemp: Int,
    val mode: Mode,
    val fanStep: Int,
    val maxRuntimeMinutes: Int,
    val minTemp: Int,
    val maxTemp: Int,
    val turboTime: List<Int>,
    val ambientTemp: Int,
    val shutdownReason: Int,
    val dualZone: Boolean?,
    val updatePhase: Int?,
    val extra: StatusExtra?,
) {
    val fanPercent
        get() = fanPercent(fanStep)
}

data class ValidationError(
    /** -1 for the program as a whole. */
    val index: Int,
    val code: ErrorCode,
    val message: String,
)

internal fun ByteArray.u(i: Int) = this[i].toInt() and 0xff

fun ByteArray.toHex() = joinToString(" ") { "%02x".format(it.toInt() and 0xff) }

fun hexToBytes(text: String): ByteArray =
    text
        .trim()
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .map { it.toInt(16).toByte() }
        .toByteArray()

private fun bytesOf(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

private fun Int.clamp(lo: Int, hi: Int) = coerceIn(lo, hi)

private fun checkSlot(slot: Int) =
    require(slot in 0 until Limits.SLOTS) { "Program slot must be 0–2, got $slot" }

object Command {
    fun button(code: Int): ByteArray {
        require(code in Button.ALLOWED) { "Refusing to encode button 0x${code.toString(16)}" }
        return bytesOf(0x01, code)
    }

    fun setMode(mode: Mode) =
        button(requireNotNull(Button.forMode(mode)) { "Mode $mode can't be set directly" })

    fun setRuntime(minutes: Int): ByteArray {
        val m = minutes.clamp(0, 255 * 60 + 59)
        return bytesOf(0x02, m / 60, m % 60)
    }

    fun setTemperature(halfC: Int) = bytesOf(0x03, halfC.clamp(0, 255))

    fun setFan(step: Int) = bytesOf(0x07, step.clamp(0, 19))

    fun setClock(hour: Int, minute: Int) = bytesOf(0x08, hour.clamp(0, 23), minute.clamp(0, 59))

    fun setLed(on: Boolean) = button(if (on) Button.LED_ON else Button.LED_OFF)

    fun setMuted(muted: Boolean) = button(if (muted) Button.MUTE else Button.UNMUTE)

    fun ack() = button(Button.ACK)

    fun recallMemory(n: Int): ByteArray {
        require(n in 1..3) { "Memory must be 1–3, got $n" }
        return button(Button.MEMORY_1 + n - 1)
    }

    fun saveMemory(n: Int): ByteArray {
        require(n in 1..3) { "Memory must be 1–3, got $n" }
        return button(Button.SAVE_MEMORY_1 + n - 1)
    }

    fun startProgram(slot: Int): ByteArray {
        checkSlot(slot)
        return button(Button.START_PROGRAM + slot)
    }

    fun deleteProgram(slot: Int): ByteArray {
        checkSlot(slot)
        return button(Button.DELETE_PROGRAM + slot)
    }

    fun query(id: Int, tag: Int) = bytesOf(0x41, id and 0xff, tag and 0xff)

    fun encodeStep(step: Step) =
        bytesOf(
            step.mode.ordinal,
            step.fan.clamp(0, 19),
            step.temp.clamp(0, 255),
            (step.hours and 0x7f) or (if (step.clock) 0x80 else 0),
            step.minutes and 0xff,
        )

    fun writeProgram(slot: Int, steps: List<Step>): List<ByteArray> {
        checkSlot(slot)
        require(steps.size <= Limits.STEPS_PER_PROGRAM) {
            "At most ${Limits.STEPS_PER_PROGRAM} steps"
        }
        val count =
            maxOf(1, (steps.size + Limits.STEPS_PER_FRAGMENT - 1) / Limits.STEPS_PER_FRAGMENT)
        return (0 until count).map { i ->
            ByteArray(43).also { frame ->
                frame[0] = 0x40
                frame[1] = Query.fragment(slot, i).toByte()
                frame[2] = steps.size.toByte()
                steps
                    .drop(i * Limits.STEPS_PER_FRAGMENT)
                    .take(Limits.STEPS_PER_FRAGMENT)
                    .forEachIndexed { j, step ->
                        encodeStep(step).copyInto(frame, 3 + j * 5)
                    }
            }
        }
    }

    fun sanitizeName(name: String): String =
        name
            .codePoints()
            .filter { it in 32..126 }
            .limit(Limits.NAME_LENGTH.toLong())
            .collect(::StringBuilder, StringBuilder::appendCodePoint, StringBuilder::append)
            .toString()

    fun nameFrame(param: Int, name: String): ByteArray {
        require(param in 0..6) { "Name param must be 0–6, got $param" }
        val frame = ByteArray(19)
        frame[0] = 0x40
        frame[1] = param.toByte()
        frame[2] = 0x10
        sanitizeName(name).forEachIndexed { i, ch -> frame[3 + i] = ch.code.toByte() }
        return frame
    }
}

object Reply {
    /** Latin-1, up to the first NUL. */
    fun parseText(payload: ByteArray): String = buildString {
        for (b in payload) {
            val c = b.toInt() and 0xff
            if (c == 0) break
            append(c.toChar())
        }
    }

    fun parseNames(payload: ByteArray): List<String?> =
        (payload.indices step 16).map { i ->
            val slot = payload.copyOfRange(i, minOf(i + 16, payload.size))
            if (slot[0].toInt() == 0x01) null else parseText(slot)
        }

    fun parseFragment(payload: ByteArray, index: Int): Fragment? {
        if (payload.isEmpty() || payload.u(0) > Limits.STEPS_PER_PROGRAM) return null
        val total = payload.u(0)
        val expected =
            (total - index * Limits.STEPS_PER_FRAGMENT).clamp(0, Limits.STEPS_PER_FRAGMENT)
        if (payload.size < 1 + expected * 5) return null
        val steps =
            (0 until expected).map { i ->
                val o = 1 + i * 5
                val mode = Mode.of(payload.u(o)) ?: return null
                Step(
                    mode = mode,
                    fan = payload.u(o + 1),
                    temp = payload.u(o + 2),
                    hours = payload.u(o + 3) and 0x7f,
                    minutes = payload.u(o + 4),
                    clock = payload.u(o + 3) and 0x80 != 0,
                )
            }
        return Fragment(total, steps)
    }

    fun isStatusStart(b: ByteArray) = b.size >= 20 && b.u(1) == 0x56 && b.u(3) == 0x01

    fun parseStatus(b: ByteArray): Status? {
        if (!isStatusStart(b)) return null
        val mode = Mode.of(b.u(9)) ?: return null
        val extra =
            if (b.size >= 30) {
                val flags = b.u(27)
                StatusExtra(
                    flags = flags,
                    connTestPassed = flags and 0x20 != 0,
                    ledEnabled = flags and 0x10 != 0,
                    unitsSetUp = flags and 0x04 != 0,
                    beepsMuted = flags and 0x01 != 0,
                    programStep = b.u(28),
                    notification = b.u(29),
                )
            } else null
        return Status(
            partial = b.u(0) == 1,
            length = b.size,
            remainingSeconds = b.u(4) * 3600 + b.u(5) * 60 + b.u(6),
            actualTemp = b.u(7),
            targetTemp = b.u(8),
            mode = mode,
            fanStep = b.u(10),
            maxRuntimeMinutes = b.u(11) * 60 + b.u(12),
            minTemp = b.u(13),
            maxTemp = b.u(14),
            turboTime = listOf(b.u(15), b.u(16)),
            ambientTemp = b.u(17),
            shutdownReason = b.u(18),
            dualZone = if (b.size > 22) b.u(22) == 0x02 else null,
            updatePhase = if (b.size > 26) b.u(26) else null,
            extra = extra,
        )
    }
}

// ---- Limits and validation ----

fun heatHours(mode: Mode, temp: Int, fanStep: Int): Int {
    if (mode != Mode.HEAT || temp < 60) return 12
    val row = HEAT_TABLE[fanStep.clamp(0, 19)]
    val offset = temp - 60
    val b = row[minOf(offset shr 1, row.size - 1)]
    return (if (offset and 1 != 0) b else b shr 4) and 15
}

fun longestStepMinutes(mode: Mode, temp: Int, fanStep: Int) =
    minOf(heatHours(mode, temp, fanStep) * 60, Limits.MAX_STEP_MINUTES)

fun fitsUnitLimits(step: Step) =
    step.clock || step.totalMinutes <= longestStepMinutes(step.mode, step.temp, step.fan)

/** Live limits when the status reports them for this mode, else the official app's table. */
fun tempRange(mode: Mode, status: Status?): Pair<TempRange, Boolean> {
    val live = status?.takeIf { it.mode == mode && mode.usesTemperature }
    if (live != null && live.minTemp > 0 && live.maxTemp >= live.minTemp)
        return TempRange(live.minTemp, live.maxTemp) to true
    return TEMP_RANGES.getValue(mode) to false
}

private fun fmtHM(minutes: Int) = "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}"

/** Problems with a step on its own; the timeline check lives in [validateProgram]. */
fun stepProblem(step: Step): String? = rangeProblem(step) ?: modeProblem(step)

private fun rangeProblem(step: Step): String? =
    when {
        step.mode == Mode.STANDBY -> "Off is not a program step; use Wait."
        step.fan !in 0..19 -> "Fan must be 5–100 %."
        step.temp !in 0..255 -> "Invalid temperature."
        step.minutes !in 0..59 || step.hours < 0 -> "Invalid time."
        step.clock && !step.mode.clockTimeAllowed ->
            "${step.mode.label} steps can't run until a clock time."
        step.clock && step.hours > 23 -> "Clock time hours must be 0–23."
        !step.clock && step.totalMinutes < 1 -> "A step needs a duration."
        else -> null
    }

private fun modeProblem(step: Step): String? {
    val (mode, _, temp) = step
    return when {
        mode == Mode.WAIT && temp != 80 -> "Wait steps use temperature 80 (40 °C)."
        mode == Mode.TURBO && temp != 86 -> "Turbo steps use temperature 86 (43 °C)."
        mode == Mode.TURBO && step.totalMinutes > Limits.TURBO_MAX_MINUTES ->
            "Turbo runs 1–10 minutes."
        mode.usesTemperature ->
            TEMP_RANGES.getValue(mode).let { r ->
                if (temp < r.min || temp > r.max)
                    "${mode.label} temperature must be ${formatTemp(r.min)}–${formatTemp(r.max)}."
                else null
            }
        else -> null
    }
}

/**
 * Empty when valid. [nowMinutes] (since midnight) anchors the timeline like the official editor
 * does: clock-time steps must end < 12 h after the previous step's end, counted from now (wrapping
 * midnight).
 */
fun validateProgram(steps: List<Step>, nowMinutes: Int): List<ValidationError> {
    if (steps.isEmpty())
        return listOf(
            ValidationError(-1, ErrorCode.INVALID_PROGRAM, "Add at least one step first.")
        )
    val errors = mutableListOf<ValidationError>()
    if (steps.size > Limits.STEPS_PER_PROGRAM) {
        errors +=
            ValidationError(
                -1,
                ErrorCode.INVALID_PROGRAM,
                "A program holds up to ${Limits.STEPS_PER_PROGRAM} steps.",
            )
    }
    var end = nowMinutes % 1440
    steps.forEachIndexed { index, step ->
        val problem = stepProblem(step)
        if (problem != null) {
            errors += ValidationError(index, ErrorCode.INVALID_PROGRAM, problem)
            return@forEachIndexed
        }
        if (step.clock) {
            val delta = Math.floorMod(step.totalMinutes - end, 1440)
            if (delta == 0 || delta >= 720) {
                errors +=
                    ValidationError(
                        index,
                        ErrorCode.STEP_TOO_LONG,
                        "Ends at ${fmtHM(step.totalMinutes)}, which is not within 12 h of the previous step's end (${fmtHM(end)}).",
                    )
            }
            end = (end + delta) % 1440
            return@forEachIndexed
        }
        val longest = longestStepMinutes(step.mode, step.temp, step.fan)
        if (step.totalMinutes > longest) {
            errors +=
                ValidationError(
                    index,
                    ErrorCode.STEP_TOO_LONG,
                    if (step.mode == Mode.HEAT && longest < Limits.MAX_STEP_MINUTES)
                        "At this temperature and fan, Heat runs at most ${longest / 60} h."
                    else "A step must be shorter than 12 h.",
                )
        }
        end = (end + step.totalMinutes) % 1440
    }
    return errors
}

// ---- Display ----

fun fanPercent(step: Int) = (step + 1) * 5

fun fanStepFromPercent(percent: Int) = ((percent / 5.0).roundToLong().toInt() - 1).clamp(0, 19)

fun toFahrenheit(halfC: Int) = halfC * 0.9 + 32

fun fromCelsius(c: Double) = (c * 2).roundToLong().toInt()

fun fromFahrenheit(f: Double) = ((f - 32) / 0.9).roundToLong().toInt()

enum class TempUnit {
    C,
    F,
}

fun formatTemp(halfC: Int?, unit: TempUnit = TempUnit.C, withUnit: Boolean = true): String {
    if (halfC == null) return "--"
    val value =
        when (unit) {
            TempUnit.F -> Math.round(toFahrenheit(halfC)).toString()
            TempUnit.C ->
                if (halfC % 2 != 0) "%.1f".format(java.util.Locale.ROOT, halfC / 2.0)
                else (halfC / 2).toString()
        }
    return if (withUnit) "$value°$unit" else value
}
