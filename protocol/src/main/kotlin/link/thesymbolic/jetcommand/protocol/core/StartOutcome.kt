package link.thesymbolic.jetcommand.protocol.core

/** How [BedJetDevice.startProgram] ended. */
sealed interface StartOutcome {
    /** [status] is the first one that showed the program running. */
    data class Started(val status: Status) : StartOutcome

    data class Failed(val reason: StartFailure) : StartOutcome
}

/** Why a program didn't start; [code] and [text] for surfaces that report errors. */
enum class StartFailure(val code: ErrorCode, val text: String = code.text) {
    CLOCK_NOT_SET(ErrorCode.CLOCK_NOT_SET),
    STEP_TOO_LONG(ErrorCode.STEP_TOO_LONG),
    UNCONFIRMED(ErrorCode.TIMEOUT, "Your BedJet didn't confirm that the program started."),
}
