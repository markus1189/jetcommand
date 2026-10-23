package link.thesymbolic.jetcommand.protocol

import kotlin.math.sign

data class Program(val name: String, val steps: List<Step>)

data class Memory(val name: String, val mode: Mode, val fan: Int, val temp: Int)

/**
 * A BedJet that speaks the same bytes, for demo mode and tests: port of the web app's `BedJetSim`.
 * Only the device; connecting and notifying are the transport's job. Time moves only through [tick]
 * and [skipAhead], so callers own the clock.
 */
class BedJetSim(
    var deviceName: String = "BEDJET-DEMO",
    slots: List<Program?> = listOf(null, null, null),
    memories: List<Memory> =
        listOf(
            Memory("Cool down", Mode.COOL, fan = 9, temp = 44),
            Memory("Warm up", Mode.HEAT, fan = 9, temp = 70),
            Memory("Dry sheets", Mode.DRY, fan = 14, temp = 50),
        ),
    /** Seconds since midnight on the unit's clock; null until `08 hh mm` sets it. */
    var clock: Int? = null,
) {
    val firmware = "V1.20 (demo)"
    val slots = slots.toMutableList()
    val memories = memories.toMutableList()

    var remainingSeconds = 0
    var actual = 46
    var target = 60
    var mode = Mode.STANDBY
    var fan = 7
    var maxRuntime = 0
    var min = 20
    var max = 80
    var ambient = 45
    var shutdown = 0
    var led = true
    var muted = false
    var step = 0
    var notification = 0

    private class Run(val slot: Int, var index: Int, var left: Int)

    private val pending = mutableMapOf<Int, MutableList<Step>>()
    private var run: Run? = null

    /** A write to the command characteristic. */
    fun send(b: ByteArray) {
        if (b.size < 2) throw BedJetException(ErrorCode.BAD_RESPONSE)
        when (b.u(0)) {
            0x01 -> press(b.u(1))
            0x02 -> if (b.size >= 3) remainingSeconds = minOf(b.u(1) * 60 + b.u(2), maxRuntime) * 60
            0x03 -> {
                target = if (mode.usesTemperature) b.u(1).coerceIn(min, max) else b.u(1)
                limitHeat()
            }
            0x07 -> {
                fan = minOf(19, b.u(1))
                limitHeat()
            }
            0x08 -> if (b.size >= 3) clock = b.u(1) * 3600 + b.u(2) * 60
            0x40 -> store(b)
        }
    }

    /** The payload the unit answers a `41 id tag` query with, without the id and tag header. */
    fun query(id: Int): ByteArray {
        fun text(value: String) =
            ByteArray(value.length + 1) { if (it < value.length) value[it].code.toByte() else 0 }
        fun nameBlock(names: List<String?>) =
            ByteArray(48).also { out ->
                names.forEachIndexed { i, name ->
                    if (name == null) out[i * 16] = 0x01
                    else
                        Command.sanitizeName(name).forEachIndexed { j, ch ->
                            out[i * 16 + j] = ch.code.toByte()
                        }
                }
            }
        return when (id) {
            Query.DEVICE_NAME -> text(deviceName)
            Query.FIRMWARE -> text(firmware)
            Query.MEMORY_NAMES -> nameBlock(memories.map { it.name })
            Query.PROGRAM_NAMES -> nameBlock(slots.map { it?.name })
            in 7 until 19 -> {
                val slot = (id - 7) / 4
                val index = (id - 7) % 4
                val steps = slots[slot]?.steps.orEmpty()
                val frames = Command.writeProgram(slot, steps)
                if (index < frames.size) frames[index].copyOfRange(2, 43)
                else byteArrayOf(steps.size.toByte())
            }
            else -> throw BedJetException(ErrorCode.BAD_RESPONSE)
        }
    }

    /**
     * What the real unit does when it cuts out: standby with a reason byte (status byte 18), no
     * command sent.
     */
    fun cutOut(reason: Int) {
        run = null
        step = 0
        mode = Mode.STANDBY
        remainingSeconds = 0
        maxRuntime = 0
        shutdown = reason and 0xff
    }

    /**
     * One second: a running program or timer moves on, the air drifts one half-degree toward its
     * goal.
     */
    fun tick() {
        advance(1)
        val goal = if (mode == Mode.STANDBY || mode == Mode.WAIT) ambient else target
        actual += (goal - actual).sign
    }

    /** Pretends [minutes] have passed without the air temperature changing. */
    fun skipAhead(minutes: Double) = advance(Math.round(minutes * 60).toInt())

    /** The 30-byte status notification. */
    fun packet() =
        ByteArray(30).also { b ->
            fun set(i: Int, v: Int) {
                b[i] = v.toByte()
            }
            set(1, 0x56)
            set(2, b.size)
            set(3, 0x01)
            set(4, remainingSeconds / 3600)
            set(5, remainingSeconds / 60 % 60)
            set(6, remainingSeconds % 60)
            set(7, actual)
            set(8, target)
            set(9, mode.ordinal)
            set(10, fan)
            set(11, maxRuntime / 60)
            set(12, maxRuntime % 60)
            set(13, min)
            set(14, max)
            set(17, ambient)
            set(18, shutdown)
            set(27, 0x20 or 0x04 or (if (led) 0x10 else 0) or (if (muted) 0x01 else 0))
            set(28, step)
            set(29, notification)
        }

    private fun press(code: Int) {
        when (code) {
            in Button.OFF..Button.EXT_HEAT -> {
                run = null
                step = 0
                enterManual(
                    listOf(Mode.STANDBY, Mode.COOL, Mode.HEAT, Mode.TURBO, Mode.DRY, Mode.EXT_HEAT)[
                        code - 1]
                )
            }
            Button.FAN_UP,
            Button.FAN_DOWN -> {
                fan = (fan + if (code == Button.FAN_UP) 1 else -1).coerceIn(0, 19)
                limitHeat()
            }
            in Button.TEMP_UP_C..Button.TEMP_DOWN_F -> nudgeTarget(code)
            in Button.MEMORY_1..Button.MEMORY_3 -> recall(memories[code - Button.MEMORY_1])
            in Button.SAVE_MEMORY_1..Button.SAVE_MEMORY_3 -> {
                val i = code - Button.SAVE_MEMORY_1
                memories[i] = Memory(memories[i].name, mode, fan, target)
            }
            Button.LED_ON,
            Button.LED_OFF -> led = code == Button.LED_ON
            Button.MUTE,
            Button.UNMUTE -> muted = code == Button.MUTE
            Button.ACK -> notification = Notification.NONE
            in Button.START_PROGRAM until Button.START_PROGRAM + 3 ->
                start(code - Button.START_PROGRAM)
            in Button.DELETE_PROGRAM until Button.DELETE_PROGRAM + 3 ->
                slots[code - Button.DELETE_PROGRAM] = null
        }
    }

    private fun nudgeTarget(code: Int) {
        if (!mode.usesTemperature) return
        val delta =
            when (code) {
                Button.TEMP_UP_C -> 2
                Button.TEMP_DOWN_C -> -2
                Button.TEMP_UP_F -> 1
                else -> -1
            }
        target = (target + delta).coerceIn(min, max)
        limitHeat()
    }

    private fun recall(memory: Memory) {
        run = null
        step = 0
        fan = memory.fan
        target = memory.temp
        enterManual(memory.mode)
    }

    private fun start(slot: Int) {
        val program = slots[slot] ?: return
        // Like the real unit: refuse up front rather than stop partway through the night.
        val now =
            clock
                ?: run {
                    notification = Notification.PROGRAM_NEEDS_CLOCK
                    return
                }
        if (validateProgram(program.steps, now / 60).any { it.code == ErrorCode.STEP_TOO_LONG }) {
            notification = Notification.PROGRAM_TOO_LONG
            return
        }
        run = Run(slot, index = -1, left = 0)
        nextStep()
    }

    private fun nextStep() {
        val current = run
        val program = current?.let { slots[it.slot] }
        if (current == null || program == null || current.index + 1 >= program.steps.size) {
            run = null
            step = 0
            enter(Mode.STANDBY, 0)
            return
        }
        current.index += 1
        val next = program.steps[current.index]
        val minutes =
            if (next.clock) {
                Math.floorMod(next.totalMinutes - (clock ?: 0) / 60, 1440).takeIf { it != 0 }
                    ?: 1440
            } else next.totalMinutes
        current.left = minutes * 60
        enter(next.mode, minutes)
        fan = next.fan
        target = next.temp
        step = current.index + 1
    }

    private fun enterManual(mode: Mode) {
        enter(mode, if (mode == Mode.STANDBY) 0 else if (mode == Mode.TURBO) 10 else 60)
        // Ceilings are illustrative, as in Lull's DemoLink: manual modes start at an hour and can
        // be stretched.
        maxRuntime =
            when (mode) {
                Mode.STANDBY -> 0
                Mode.TURBO -> 10
                Mode.HEAT -> 240
                else -> 720
            }
        limitHeat()
    }

    private fun enter(mode: Mode, minutes: Int) {
        this.mode = mode
        remainingSeconds = minutes * 60
        maxRuntime = minutes
        if (mode.usesTemperature) {
            val range = TEMP_RANGES.getValue(mode)
            min = range.min
            max =
                if (mode == Mode.DRY) minOf(range.max, maxOf(range.min, ambient + 10))
                else range.max
            target = target.coerceIn(min, max)
        } else {
            min = 20
            max = 80
            if (mode == Mode.TURBO) target = 86
        }
    }

    // Hotter air at a higher fan is cut off sooner, so manual Heat's ceiling follows the heat
    // table.
    private fun limitHeat() {
        if (mode != Mode.HEAT || run != null) return
        maxRuntime = minOf(240, longestStepMinutes(Mode.HEAT, target, fan))
        remainingSeconds = minOf(remainingSeconds, maxRuntime * 60)
    }

    private fun advance(total: Int) {
        clock = clock?.let { (it + total) % 86400 }
        var seconds = total
        while (seconds > 0) {
            val current = run
            if (current != null) {
                val take = minOf(seconds, current.left)
                current.left -= take
                remainingSeconds = current.left
                seconds -= take
                if (current.left <= 0) nextStep()
            } else {
                if (mode != Mode.STANDBY && mode != Mode.WAIT) {
                    remainingSeconds = maxOf(0, remainingSeconds - seconds)
                    if (remainingSeconds == 0) enter(Mode.STANDBY, 0)
                }
                seconds = 0
            }
        }
    }

    private fun store(b: ByteArray) {
        if (b.size < 3) return
        val target = b.u(1)
        when {
            target <= 3 -> {
                val name = Reply.parseText(b.copyOfRange(3, b.size))
                if (target == 0) deviceName = name
                else memories[target - 1] = memories[target - 1].copy(name = name)
            }
            target <= 6 -> {
                val slot = target - 4
                val name = Reply.parseText(b.copyOfRange(3, b.size))
                val steps = pending.remove(slot)
                if (steps != null) slots[slot] = Program(name, steps.toList())
                else slots[slot]?.let { slots[slot] = it.copy(name = name) }
            }
            target < 19 -> {
                val slot = (target - 7) / 4
                val index = (target - 7) % 4
                val fragment = Reply.parseFragment(b.copyOfRange(2, b.size), index) ?: return
                // Not cleared on completion: a later name frame for this slot re-commits these
                // steps, as on the JS side.
                val steps = if (index == 0) mutableListOf() else pending[slot] ?: mutableListOf()
                steps += fragment.steps
                pending[slot] = steps
                if (steps.size >= fragment.total)
                    slots[slot] = Program(slots[slot]?.name ?: "", steps.toList())
            }
        }
    }
}
