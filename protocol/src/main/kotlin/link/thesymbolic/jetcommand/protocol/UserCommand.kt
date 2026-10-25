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
 * Pure: the whole policy for [command] from [origin]; the controller only interprets the result.
 */
fun plan(command: UserCommand, origin: CommandOrigin, snapshot: CommandSnapshot): Plan =
    TODO("protocol agent")

/** The log and feedback name of [command], e.g. "start program 2". */
fun describe(command: UserCommand): String = TODO("protocol agent")
