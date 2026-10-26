package link.thesymbolic.jetcommand

import link.thesymbolic.jetcommand.protocol.SettableMode
import link.thesymbolic.jetcommand.protocol.Slot
import link.thesymbolic.jetcommand.protocol.UserCommand

/**
 * Commands as intent data, e.g. `jetcommand:program/2`; shortcuts.xml spells these out, so the
 * format is an API. Plain strings, not android.net.Uri, so it runs in JVM tests.
 */
object CommandUri {
    const val ACTION = "link.thesymbolic.jetcommand.COMMAND"
    private const val SCHEME = "jetcommand:"

    fun of(command: UserCommand): String =
        SCHEME +
            when (command) {
                is UserCommand.SetMode -> "mode/${command.mode.name}"
                is UserCommand.SetTemperature -> "temp/${command.halfC}"
                is UserCommand.SetFan -> "fan/${command.step}"
                // 1-based, as the app and the unit's display show it.
                is UserCommand.StartProgram -> "program/${command.slot.number}"
                is UserCommand.Press -> "press/${command.button}"
                is UserCommand.SetRuntime -> "runtime/${command.minutes}"
            }

    /** Null for anything malformed; the caller logs it, an external sender can't be trusted. */
    fun parse(uri: String?): UserCommand? {
        val parts = uri?.removePrefix(SCHEME)?.takeIf { it != uri }?.split('/') ?: return null
        if (parts.size != 2) return null
        val (kind, arg) = parts
        val n = arg.toIntOrNull()
        return when (kind) {
            "mode" -> SettableMode.entries.find { it.name == arg }?.let(UserCommand::SetMode)
            "temp" -> n?.let(UserCommand::SetTemperature)
            "fan" -> n?.let(UserCommand::SetFan)
            "program" -> n?.let { Slot.of(it - 1) }?.let(UserCommand::StartProgram)
            "press" -> n?.let(UserCommand::Press)
            "runtime" -> n?.let(UserCommand::SetRuntime)
            else -> null
        }
    }
}
