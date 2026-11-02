package link.thesymbolic.jetcommand.protocol

/** What a user asks of the unit, from any surface; [plan] decides whether and how it runs. */
sealed interface UserCommand {
    data class SetMode(val mode: SettableMode) : UserCommand

    data class SetTemperature(val target: TargetTemp) : UserCommand

    data class SetFan(val fan: FanStep) : UserCommand

    data class StartProgram(val slot: Slot) : UserCommand

    /** The unit clamps [autoOff] to its own ceiling for the mode, temp and fan. */
    data class SetRuntime(val autoOff: AutoOff) : UserCommand

    /** Relative (Temp +, Fan +): only from surfaces that show the state it changes. */
    data class Press(val key: Key) : UserCommand
}

/** One of the unit's 20 fan steps, 5–100 %. */
@JvmInline
value class FanStep private constructor(val step: Int) {
    companion object {
        val RANGE = 0..19

        fun of(step: Int) = if (step in RANGE) FanStep(step) else null
    }
}

/**
 * A target in half °C within any mode's range, since the current mode is unknown where commands are
 * made; the unit clamps to the mode's own range.
 */
@JvmInline
value class TargetTemp private constructor(val halfC: Int) {
    companion object {
        val RANGE = TEMP_RANGES.values.minOf { it.min }..TEMP_RANGES.values.maxOf { it.max }

        fun of(halfC: Int) = if (halfC in RANGE) TargetTemp(halfC) else null
    }
}

/** Auto off in [minutes] from now. */
@JvmInline
value class AutoOff private constructor(val minutes: Int) {
    companion object {
        fun of(minutes: Int) = if (minutes in RUNTIME_MINUTES) AutoOff(minutes) else null
    }
}

/** A command from a source that carries raw numbers (intent data, sliders). */
sealed interface CommandRequest {
    data class Valid(val command: UserCommand) : CommandRequest

    /** A number outside its type; [plan] rejects it with [reason], phrased for the user. */
    data class OutOfRange(val label: String, val reason: String) : CommandRequest

    companion object {
        fun fan(step: Int): CommandRequest =
            FanStep.of(step)?.let { Valid(UserCommand.SetFan(it)) }
                ?: OutOfRange(
                    fanLabel(step),
                    "Fan must be ${fanPercent(FanStep.RANGE.first)}–" +
                        "${fanPercent(FanStep.RANGE.last)} %.",
                )

        fun temperature(halfC: Int): CommandRequest =
            TargetTemp.of(halfC)?.let { Valid(UserCommand.SetTemperature(it)) }
                ?: OutOfRange(
                    tempLabel(halfC),
                    "Temperature must be " +
                        "${formatTemp(TargetTemp.RANGE.first, withUnit = false)}–" +
                        "${formatTemp(TargetTemp.RANGE.last)}.",
                )

        fun runtime(minutes: Int): CommandRequest =
            AutoOff.of(minutes)?.let { Valid(UserCommand.SetRuntime(it)) }
                ?: OutOfRange(
                    runtimeLabel(minutes),
                    "Auto off must be 1 min to ${RUNTIME_MINUTES.last / 60} h.",
                )
    }
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
    /** Run [command]; [label] names it in the log and feedback. */
    sealed interface Run : Plan {
        val command: UserCommand
        val label: String

        /** Show the outcome outside the app. */
        val report: Boolean

        /** On the current stack at once; failures go to the log. */
        data class Now(override val command: UserCommand, override val label: String) : Run {
            override val report
                get() = false
        }

        /**
         * Wait up to [readyTimeoutMs] for Ready, connecting to the remembered device first if
         * [connect]; the outcome is reported.
         */
        data class WhenReady(
            override val command: UserCommand,
            override val label: String,
            val connect: Boolean,
        ) : Run {
            override val report
                get() = true
        }
    }

    /** Not run; [reason] is shown to the user as is. */
    data class Rejected(val label: String, val reason: String, val report: Boolean) : Plan
}

/** How a command that [plan] let through ended. */
sealed interface RunOutcome {
    data object Ran : RunOutcome

    /** Sent, but the unit didn't confirm it in time; [note] as reported. */
    data class Unconfirmed(val note: String) : RunOutcome

    /** [error] as logged and reported. */
    data class Failed(val error: String) : RunOutcome
}

/**
 * A program start as a command's outcome. Unconfirmed starts usually run, so only a
 * [RunOutcome.Failed] means the program didn't start.
 */
fun runOutcomeOf(started: StartOutcome): RunOutcome =
    when (started) {
        is StartOutcome.Started -> RunOutcome.Ran
        is StartOutcome.Failed ->
            if (started.reason == StartFailure.UNCONFIRMED)
                RunOutcome.Unconfirmed("sent, not confirmed")
            else RunOutcome.Failed(started.reason.text)
    }

/**
 * How long a shortcut waits for Ready on a link with [config]: a connect that times out, the
 * longest backoff and the retry must fit, or a unit that is just waking up fails the command.
 */
fun readyTimeoutMs(config: BedJetLink.Config) = 2 * config.connectTimeoutMs + config.reconnectMaxMs

// The protocol's 12 h step limit; turbo's minute is the shortest the official app sets.
val RUNTIME_MINUTES = 1..12 * 60

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
        if (unseen) Plan.Run.WhenReady(command, label, connect) else Plan.Run.Now(command, label)

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

/** [plan] for a request: out-of-range numbers are rejected before the connection is looked at. */
fun plan(request: CommandRequest, origin: CommandOrigin, snapshot: CommandSnapshot): Plan =
    when (request) {
        is CommandRequest.Valid -> plan(request.command, origin, snapshot)
        is CommandRequest.OutOfRange ->
            Plan.Rejected(request.label, request.reason, report = origin == CommandOrigin.SHORTCUT)
    }

// Unseen commands mean the real unit, so the demo doesn't count as connected for them.
private fun usable(snapshot: CommandSnapshot, unseen: Boolean) =
    snapshot.connected && !(unseen && snapshot.demo)

private const val PRESS = "Shortcuts can't step up or down; set a temperature or fan level."

/** The log and feedback name of [command], e.g. "start program 2". */
fun describe(command: UserCommand): String =
    when (command) {
        is UserCommand.SetMode ->
            if (command.mode == SettableMode.STANDBY) "off" else "mode ${command.mode.mode.label}"
        is UserCommand.SetTemperature -> tempLabel(command.target.halfC)
        is UserCommand.SetFan -> fanLabel(command.fan.step)
        is UserCommand.StartProgram -> "start program ${command.slot.number}"
        is UserCommand.Press -> "press ${buttonName(command.key.button)}"
        is UserCommand.SetRuntime -> runtimeLabel(command.autoOff.minutes)
    }

private fun tempLabel(halfC: Int) = "temp ${formatTemp(halfC)}"

private fun fanLabel(step: Int) = "fan ${fanPercent(step)}%"

private fun runtimeLabel(minutes: Int) =
    "auto off in %d:%02d".format(java.util.Locale.ROOT, minutes / 60, minutes % 60)

// The night log's names, through its public frame describer; codes it doesn't know get their hex.
private fun buttonName(code: Int): String =
    describeCommand(byteArrayOf(0x01, code.toByte())).takeUnless { it == "button" }
        ?: "button 0x%02x".format(java.util.Locale.ROOT, code)
