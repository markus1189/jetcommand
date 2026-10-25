package link.thesymbolic.jetcommand.protocol

/** What a user asks of the unit, from any surface; [plan] decides whether and how it runs. */
sealed interface UserCommand {
    data class SetMode(val mode: Mode) : UserCommand

    data class SetTemperature(val halfC: Int) : UserCommand

    data class SetFan(val step: Int) : UserCommand

    data class StartProgram(val slot: Slot) : UserCommand

    /** Relative (Temp +, Fan +): only from surfaces that show the state it changes. */
    data class Press(val button: Int) : UserCommand
}

enum class CommandOrigin {
    /** The app's own screens. */
    APP,
    /** The ongoing notification's buttons; shown only while the link is Ready. */
    NOTIFICATION,
    /** Launcher shortcuts, and through them Samsung Routines and other automation. */
    SHORTCUT,
}

/** What [plan] needs to know about the controller at the moment the command arrives. */
data class CommandSnapshot(
    /** A stack exists (demo or device), whatever its link state. */
    val connected: Boolean,
    /** That stack is the Demo BedJet. */
    val demo: Boolean,
    val link: LinkState,
    /** A real device was connected before, so the controller can reconnect to it. */
    val remembered: Boolean,
    val hasConnectPermission: Boolean,
)

sealed interface Plan {
    /**
     * Run [command]: connect to the remembered device first if [connect], then wait up to
     * [readyTimeoutMs] for Ready (null: run at once, failures go to the log). [label] names it in
     * the log and feedback; [report] shows the outcome outside the app.
     */
    data class Run(
        val command: UserCommand,
        val label: String,
        val connect: Boolean,
        val readyTimeoutMs: Long?,
        val report: Boolean,
    ) : Plan

    /** Not run; [reason] is shown to the user as is. */
    data class Rejected(val label: String, val reason: String, val report: Boolean) : Plan
}

/**
 * How long a shortcut waits for Ready: one reconnect cycle (20 s connect timeout, 10 s backoff, 20
 * s retry) must fit, or a unit that is just waking up fails the command.
 */
const val READY_TIMEOUT_MS = 45_000L

// The unit's 20 fan steps, 5–100 %; Codec.kt clamps every fan value to this.
private val FAN_STEPS = 0..19

// Overall, since the current mode is unknown here; the unit clamps to the mode's own range.
private val TEMP_LIMITS = TEMP_RANGES.values.minOf { it.min }..TEMP_RANGES.values.maxOf { it.max }

/**
 * Pure: the whole policy for [command] from [origin]; the controller only interprets the result.
 * Shortcuts run unseen, so they get the outcome reported, wait for Ready instead of failing fast,
 * may reconnect, never run on the Demo BedJet (a routine means the real unit), and refuse relative
 * presses whose effect nobody can check.
 */
fun plan(command: UserCommand, origin: CommandOrigin, snapshot: CommandSnapshot): Plan {
    val label = describe(command)
    val unseen = origin == CommandOrigin.SHORTCUT
    fun reject(reason: String) = Plan.Rejected(label, reason, report = unseen)
    fun run(connect: Boolean) =
        Plan.Run(
            command,
            label,
            connect = connect,
            readyTimeoutMs = if (unseen) READY_TIMEOUT_MS else null,
            report = unseen,
        )

    rangeProblem(command)?.let {
        return reject(it)
    }
    return when {
        usable(snapshot, unseen) ->
            if (unseen && command is UserCommand.Press) reject(PRESS) else run(false)
        !unseen -> reject("Not connected")
        command is UserCommand.Press -> reject(PRESS)
        !snapshot.remembered -> reject("Connect to your BedJet in the app once first.")
        !snapshot.hasConnectPermission -> reject("Open JetCommand to allow Bluetooth.")
        else -> run(true)
    }
}

// Unseen commands mean the real unit, so the demo doesn't count as connected for them.
private fun usable(snapshot: CommandSnapshot, unseen: Boolean) =
    snapshot.connected && !(unseen && snapshot.demo)

private const val PRESS = "Shortcuts can't step up or down; set a temperature or fan level."

private fun rangeProblem(command: UserCommand): String? =
    when (command) {
        is UserCommand.SetFan ->
            if (command.step in FAN_STEPS) null
            else "Fan must be ${fanPercent(FAN_STEPS.first)}–${fanPercent(FAN_STEPS.last)} %."
        is UserCommand.SetTemperature ->
            if (command.halfC in TEMP_LIMITS) null
            else
                "Temperature must be ${formatTemp(TEMP_LIMITS.first, withUnit = false)}–" +
                    "${formatTemp(TEMP_LIMITS.last)}."
        is UserCommand.Press -> if (command.button in Button.ALLOWED) null else "Unknown button."
        else -> null
    }

/** The log and feedback name of [command], e.g. "start program 2". */
fun describe(command: UserCommand): String =
    when (command) {
        is UserCommand.SetMode ->
            if (command.mode == Mode.STANDBY) "off" else "mode ${command.mode.label}"
        is UserCommand.SetTemperature -> "temp ${formatTemp(command.halfC)}"
        is UserCommand.SetFan -> "fan ${fanPercent(command.step)}%"
        is UserCommand.StartProgram -> "start program ${command.slot.number}"
        is UserCommand.Press -> "press ${buttonName(command.button)}"
    }

// The night log's names, through its public frame describer; codes it doesn't know get their hex.
private fun buttonName(code: Int): String =
    describeCommand(byteArrayOf(0x01, code.toByte())).takeUnless { it == "button" }
        ?: "button 0x%02x".format(java.util.Locale.ROOT, code)
