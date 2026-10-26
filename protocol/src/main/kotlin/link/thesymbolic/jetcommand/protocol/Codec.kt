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
    private const val START_PROGRAM = 0x80
    private const val DELETE_PROGRAM = 0x83

    fun startProgram(slot: Slot) = START_PROGRAM + slot.index

    fun deleteProgram(slot: Slot) = DELETE_PROGRAM + slot.index

    fun slotOfStartProgram(code: Int): Slot? = Slot.of(code - START_PROGRAM)

    fun slotOfDeleteProgram(code: Int): Slot? = Slot.of(code - DELETE_PROGRAM)

    // A whitelist rather than a blacklist: 0x4C (factory reset) and any other unknown code can
    // never be encoded.
    val ALLOWED: Set<Int> =
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
            LED_ON,
            LED_OFF,
            MUTE,
            UNMUTE,
            ACK,
        ) +
            (MEMORY_1..MEMORY_3) +
            (SAVE_MEMORY_1..SAVE_MEMORY_3) +
            Slot.all.flatMap { listOf(startProgram(it), deleteProgram(it)) }
}

/** The modes a button sets; WAIT exists only as a program step. Names match [Mode]'s. */
enum class SettableMode(val mode: Mode, val button: Int) {
    STANDBY(Mode.STANDBY, Button.OFF),
    HEAT(Mode.HEAT, Button.HEAT),
    TURBO(Mode.TURBO, Button.TURBO),
    EXT_HEAT(Mode.EXT_HEAT, Button.EXT_HEAT),
    COOL(Mode.COOL, Button.COOL),
    DRY(Mode.DRY, Button.DRY);

    companion object {
        fun of(mode: Mode): SettableMode? = entries.find { it.mode == mode }
    }
}

/** The buttons a [UserCommand.Press] may send. */
enum class Key(val button: Int) {
    OFF(Button.OFF),
    FAN_UP(Button.FAN_UP),
    FAN_DOWN(Button.FAN_DOWN),
    TEMP_UP_C(Button.TEMP_UP_C),
    TEMP_DOWN_C(Button.TEMP_DOWN_C);

    companion object {
        fun of(button: Int): Key? = entries.find { it.button == button }
    }
}

/** Status byte 29: what the unit wants the user to see, [None] when nothing is pending. */
sealed class Notification(val code: Int, val text: String) {
    data object None : Notification(0, "")

    data object CleanFilter : Notification(1, "Time to clean the filter.")

    data object UpdateAvailable : Notification(2, "A firmware update is available.")

    data object UpdateFailed : Notification(3, "The firmware update failed.")

    // 4 and 5 are the official app's wording (MainFragment.java:370-386).
    data object ProgramNeedsClock :
        Notification(4, "The specified sequence cannot be run because the clock is not set.")

    data object ProgramTooLong :
        Notification(
            5,
            "The specified sequence cannot be run because it contains steps that would be too " +
                "long running from the current time.",
        )

    /** A code this build doesn't know; the unit still expects it acknowledged. */
    data class Unknown(val raw: Int) : Notification(raw, "BedJet notification $raw")

    companion object {
        // Lazy: the objects aren't initialised yet while the companion is.
        private val known by lazy {
            listOf(
                None,
                CleanFilter,
                UpdateAvailable,
                UpdateFailed,
                ProgramNeedsClock,
                ProgramTooLong,
            )
        }

        fun of(code: Int): Notification = known.find { it.code == code } ?: Unknown(code)
    }
}

object Query {
    const val DEVICE_NAME = 0x00
    const val MEMORY_NAMES = 0x01
    const val PROGRAM_NAMES = 0x04
    const val FIRMWARE = 0x20

    private const val FIRST_FRAGMENT = 7

    fun fragment(slot: Slot, index: Int) =
        FIRST_FRAGMENT + Limits.FRAGMENTS_PER_SLOT * slot.index + index

    /** The slot and fragment index [id] names, or null when it isn't a fragment id. */
    fun fragmentOf(id: Int): Pair<Slot, Int>? {
        val offset = id - FIRST_FRAGMENT
        if (offset < 0) return null
        val slot = Slot.of(offset / Limits.FRAGMENTS_PER_SLOT) ?: return null
        return slot to offset % Limits.FRAGMENTS_PER_SLOT
    }
}

/** One of the unit's three stored settings, numbered 1–3 as on its display. */
enum class MemorySlot {
    ONE,
    TWO,
    THREE;

    val number
        get() = ordinal + 1

    internal val recallButton
        get() = Button.MEMORY_1 + ordinal

    internal val saveButton
        get() = Button.SAVE_MEMORY_1 + ordinal

    companion object {
        fun of(number: Int): MemorySlot? = entries.getOrNull(number - 1)
    }
}

/** What a name frame renames; [param] is its byte on the wire. */
sealed interface NameTarget {
    val param: Int

    data object DeviceName : NameTarget {
        override val param = 0
    }

    data class MemoryName(val memory: MemorySlot) : NameTarget {
        override val param
            get() = memory.number
    }

    data class ProgramName(val slot: Slot) : NameTarget {
        override val param
            get() = FIRST_PROGRAM + slot.index
    }

    companion object {
        private const val FIRST_PROGRAM = 4

        fun of(param: Int): NameTarget? =
            if (param == 0) DeviceName
            else
                MemorySlot.of(param)?.let(::MemoryName)
                    ?: Slot.of(param - FIRST_PROGRAM)?.let(::ProgramName)
    }
}

object Limits {
    const val SLOTS = 3
    const val STEPS_PER_PROGRAM = 32
    const val STEPS_PER_FRAGMENT = 8
    const val FRAGMENTS_PER_SLOT = 4
    const val NAME_LENGTH = 15
    const val MAX_STEP_MINUTES = 12 * 60
    const val TURBO_MAX_MINUTES = 10
}

/** One of the unit's [Limits.SLOTS] program slots; [of] is the only way in. */
@JvmInline
value class Slot private constructor(val index: Int) {
    /** 1-based, as the app shows it. */
    val number
        get() = index + 1

    companion object {
        val all: List<Slot> = List(Limits.SLOTS, ::Slot)

        fun of(index: Int): Slot? = all.getOrNull(index)
    }
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
    CLOCK_NOT_SET(Notification.ProgramNeedsClock.text),
    STEP_TOO_LONG(Notification.ProgramTooLong.text),
    INVALID_PROGRAM("This program can't be stored on the BedJet."),
    SLOT_CHANGED("This program slot changed since it was last read. Check it and try again."),
}

class BedJetException(val code: ErrorCode, message: String = code.text, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * When a step ends. Fields hold whatever the unit sent (hours 0–127, any minute byte), so decoding
 * stays total; [stepProblem] judges ranges.
 */
sealed interface Timing {
    data class For(val hours: Int, val minutes: Int) : Timing {
        val totalMinutes
            get() = hours * 60 + minutes
    }

    /** At the unit's wall-clock [hour]:[minute]. */
    data class Until(val hour: Int, val minute: Int) : Timing {
        val minuteOfDay
            get() = hour * 60 + minute
    }
}

data class Step(val mode: Mode, val fan: Int, val temp: Int, val timing: Timing) {
    companion object {
        fun default() = Step(Mode.WAIT, fan = 19, temp = 80, Timing.For(hours = 1, minutes = 0))
    }
}

private const val STEP_BYTES = 5
private const val UNTIL_FLAG = 0x80

/** The only place the 5-byte step layout lives: mode, fan, temp, hours | [UNTIL_FLAG], minutes. */
fun encodeStep(step: Step): ByteArray {
    val (hours, minutes, flag) =
        when (val t = step.timing) {
            is Timing.For -> Triple(t.hours, t.minutes, 0)
            is Timing.Until -> Triple(t.hour, t.minute, UNTIL_FLAG)
        }
    return bytesOf(
        step.mode.ordinal,
        step.fan.clamp(0, 19),
        step.temp.clamp(0, 255),
        (hours and 0x7f) or flag,
        minutes and 0xff,
    )
}

/** The step at [offset]; null only for an unknown mode byte. */
fun decodeStep(bytes: ByteArray, offset: Int = 0): Step? {
    val mode = Mode.of(bytes.u(offset)) ?: return null
    val hours = bytes.u(offset + 3) and 0x7f
    val minutes = bytes.u(offset + 4)
    return Step(
        mode = mode,
        fan = bytes.u(offset + 1),
        temp = bytes.u(offset + 2),
        timing =
            if (bytes.u(offset + 3) and UNTIL_FLAG != 0) Timing.Until(hours, minutes)
            else Timing.For(hours, minutes),
    )
}

data class Fragment(val total: Int, val steps: List<Step>)

data class StatusExtra(
    val flags: Int,
    val connTestPassed: Boolean,
    val ledEnabled: Boolean,
    val unitsSetUp: Boolean,
    val beepsMuted: Boolean,
    val programStep: Int,
    val notification: Notification,
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

object Command {
    internal fun button(code: Int): ByteArray {
        require(code in Button.ALLOWED) { "Refusing to encode button 0x${code.toString(16)}" }
        return bytesOf(0x01, code)
    }

    fun setMode(mode: SettableMode) = button(mode.button)

    fun setRuntime(minutes: Int): ByteArray {
        val m = minutes.clamp(0, 255 * 60 + 59)
        return bytesOf(0x02, m / 60, m % 60)
    }

    fun setTemperature(halfC: Int) = bytesOf(0x03, halfC.clamp(0, 255))

    fun setFan(step: Int) = bytesOf(0x07, step.clamp(0, 19))

    fun setClock(hour: Int, minute: Int) = bytesOf(0x08, hour.clamp(0, 23), minute.clamp(0, 59))

    fun setLed(on: Boolean) = button(if (on) Button.LED_ON else Button.LED_OFF)

    fun setMuted(muted: Boolean) = button(if (muted) Button.MUTE else Button.UNMUTE)

    fun press(key: Key) = button(key.button)

    fun ack() = button(Button.ACK)

    fun recallMemory(memory: MemorySlot) = button(memory.recallButton)

    fun saveMemory(memory: MemorySlot) = button(memory.saveButton)

    fun startProgram(slot: Slot) = button(Button.startProgram(slot))

    fun deleteProgram(slot: Slot) = button(Button.deleteProgram(slot))

    fun query(id: Int, tag: Int) = bytesOf(0x41, id and 0xff, tag and 0xff)

    fun writeProgram(slot: Slot, steps: List<Step>): List<ByteArray> {
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
                        encodeStep(step).copyInto(frame, 3 + j * STEP_BYTES)
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

    fun nameFrame(target: NameTarget, name: String): ByteArray {
        val frame = ByteArray(19)
        frame[0] = 0x40
        frame[1] = target.param.toByte()
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
        if (payload.size < 1 + expected * STEP_BYTES) return null
        val steps =
            (0 until expected).map { i -> decodeStep(payload, 1 + i * STEP_BYTES) ?: return null }
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
                    notification = Notification.of(b.u(29)),
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

/**
 * The auto-off ceiling as the official app computes it (`Utils.getMaxRunTime`, its turbo picker);
 * it never reads the status's max runtime bytes.
 */
fun maxRuntimeMinutes(mode: Mode, temp: Int, fanStep: Int) =
    if (mode == Mode.TURBO) 10 else heatHours(mode, temp, fanStep) * 60

fun longestStepMinutes(mode: Mode, temp: Int, fanStep: Int) =
    minOf(heatHours(mode, temp, fanStep) * 60, Limits.MAX_STEP_MINUTES)

fun fitsUnitLimits(step: Step) =
    when (val t = step.timing) {
        is Timing.Until -> true
        is Timing.For -> t.totalMinutes <= longestStepMinutes(step.mode, step.temp, step.fan)
    }

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
        else -> timingProblem(step.mode, step.timing)
    }

private fun timingProblem(mode: Mode, t: Timing): String? =
    when (t) {
        is Timing.For ->
            when {
                t.minutes !in 0..59 || t.hours < 0 -> "Invalid time."
                t.totalMinutes < 1 -> "A step needs a duration."
                else -> null
            }
        is Timing.Until ->
            when {
                t.minute !in 0..59 || t.hour < 0 -> "Invalid time."
                !mode.clockTimeAllowed -> "${mode.label} steps can't run until a clock time."
                t.hour > 23 -> "Clock time hours must be 0–23."
                else -> null
            }
    }

private fun modeProblem(step: Step): String? {
    val (mode, _, temp) = step
    val timing = step.timing
    return when {
        mode == Mode.WAIT && temp != 80 -> "Wait steps use temperature 80 (40 °C)."
        mode == Mode.TURBO && temp != 86 -> "Turbo steps use temperature 86 (43 °C)."
        mode == Mode.TURBO &&
            timing is Timing.For &&
            timing.totalMinutes > Limits.TURBO_MAX_MINUTES -> "Turbo runs 1–10 minutes."
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
 * Empty when valid. [nowMinutes] (since midnight) anchors the timeline as the unit does at start: a
 * clock-time step may end at most 12 h after the previous step's planned end (wrapping midnight),
 * the firmware's only start check (`FUN_400ece98`). A duration step above [longestStepMinutes] is
 * the app's own rule: the unit would run it and cut it short.
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
        val minutes =
            when (val timing = step.timing) {
                is Timing.For -> timing.totalMinutes
                is Timing.Until -> {
                    val delta = Math.floorMod(timing.minuteOfDay - end, 1440)
                    if (delta > Limits.MAX_STEP_MINUTES) {
                        errors +=
                            ValidationError(
                                index,
                                ErrorCode.STEP_TOO_LONG,
                                "Ends at ${fmtHM(timing.minuteOfDay)}, which is not within 12 h of the previous step's end (${fmtHM(end)}).",
                            )
                    }
                    end = (end + delta) % 1440
                    return@forEachIndexed
                }
            }
        val longest = longestStepMinutes(step.mode, step.temp, step.fan)
        if (minutes > longest) {
            errors +=
                ValidationError(
                    index,
                    ErrorCode.STEP_TOO_LONG,
                    if (step.mode == Mode.HEAT && longest < Limits.MAX_STEP_MINUTES)
                        "At this temperature and fan, Heat runs at most ${longest / 60} h."
                    else "A step runs at most 12 h.",
                )
        }
        end = (end + minutes) % 1440
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
