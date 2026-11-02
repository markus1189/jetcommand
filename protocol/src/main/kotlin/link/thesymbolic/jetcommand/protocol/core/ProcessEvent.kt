package link.thesymbolic.jetcommand.protocol.core

/**
 * A [NightEvent.Kind.PROCESS] entry. The log stores only [detail], in the text earlier builds
 * wrote, so [parse] is the one place that text is read: `parse(s).detail == s` for every string.
 */
sealed interface ProcessEvent {
    val detail: String

    data object Started : ProcessEvent {
        override val detail = "process started"
    }

    /**
     * The service only stops when the connection ends, which a stop while reconnecting leaves
     * unlogged: this ends any open drop.
     */
    data object ServiceDestroyed : ProcessEvent {
        override val detail = "service destroyed"
    }

    /**
     * The system's record of an earlier process ending; [code] is `ApplicationExitInfo.reason`,
     * kept raw so a reason newer than [ExitReason] still round-trips.
     */
    data class Exit(val code: Int, val importance: Int, val description: String) : ProcessEvent {
        val reason: ExitReason?
            get() = ExitReason.ofCode(code)

        val killedBySystem: Boolean
            get() = reason?.killedBySystem == true

        val reasonLabel: String
            get() = reason?.label ?: "reason $code"

        override val detail
            get() = "$EXIT_PREFIX$reasonLabel importance $importance: $description"
    }

    /** Service and memory notes, and any text this build can't read as the others. */
    data class Note(override val detail: String) : ProcessEvent

    companion object {
        private const val EXIT_PREFIX = "exit "
        // Lazy reason: a description may itself contain " importance N: ".
        private val EXIT =
            Regex("""exit (.+?) importance (-?\d+): (.*)""", RegexOption.DOT_MATCHES_ALL)
        private val UNKNOWN_REASON = Regex("""reason (-?\d+)""")

        fun parse(detail: String): ProcessEvent {
            if (detail == Started.detail) return Started
            if (detail == ServiceDestroyed.detail) return ServiceDestroyed
            val exit = EXIT.matchEntire(detail) ?: return Note(detail)
            val (label, importance, description) = exit.destructured
            val code =
                ExitReason.entries.firstOrNull { it.label == label }?.code
                    ?: UNKNOWN_REASON.matchEntire(label)?.groupValues?.get(1)?.toIntOrNull()
            val parsed = code?.let { c ->
                importance.toIntOrNull()?.let { Exit(c, it, description) }
            }
            // Text no formatter of ours produces ("reason 2", "importance 007") stays as written.
            return parsed?.takeIf { it.detail == detail } ?: Note(detail)
        }
    }
}

/**
 * `ApplicationExitInfo.REASON_*`, with the label the log has always used for it. [killedBySystem]:
 * the system ended a process that wanted to live on; an update, a crash or the user stopping the
 * app is still a gap, but not a kill.
 */
enum class ExitReason(val code: Int, val label: String, val killedBySystem: Boolean = false) {
    EXIT_SELF(1, "self"),
    SIGNALED(2, "signaled", killedBySystem = true),
    LOW_MEMORY(3, "low memory", killedBySystem = true),
    CRASH(4, "crash"),
    CRASH_NATIVE(5, "native crash"),
    ANR(6, "anr"),
    INITIALIZATION_FAILURE(7, "init failure"),
    PERMISSION_CHANGE(8, "permission change"),
    EXCESSIVE_RESOURCE_USAGE(9, "excessive resource usage", killedBySystem = true),
    USER_REQUESTED(10, "user requested"),
    USER_STOPPED(11, "user stopped"),
    DEPENDENCY_DIED(12, "dependency died"),
    OTHER(13, "other"),
    FREEZER(14, "freezer"),
    PACKAGE_STATE_CHANGE(15, "package state change"),
    PACKAGE_UPDATED(16, "package updated");

    companion object {
        fun ofCode(code: Int): ExitReason? = entries.firstOrNull { it.code == code }
    }
}
