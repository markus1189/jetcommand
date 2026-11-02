package link.thesymbolic.jetcommand.night

import android.annotation.SuppressLint
import android.app.ApplicationExitInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import link.thesymbolic.jetcommand.protocol.core.ExitReason

class ExitReasonTest {
    // The constants are inlined at compile time, so newer ones are fine on any API level here.
    @SuppressLint("InlinedApi")
    @Test
    fun `codes are ApplicationExitInfo's`() {
        val android =
            mapOf(
                ExitReason.EXIT_SELF to ApplicationExitInfo.REASON_EXIT_SELF,
                ExitReason.SIGNALED to ApplicationExitInfo.REASON_SIGNALED,
                ExitReason.LOW_MEMORY to ApplicationExitInfo.REASON_LOW_MEMORY,
                ExitReason.CRASH to ApplicationExitInfo.REASON_CRASH,
                ExitReason.CRASH_NATIVE to ApplicationExitInfo.REASON_CRASH_NATIVE,
                ExitReason.ANR to ApplicationExitInfo.REASON_ANR,
                ExitReason.INITIALIZATION_FAILURE to
                    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
                ExitReason.PERMISSION_CHANGE to ApplicationExitInfo.REASON_PERMISSION_CHANGE,
                ExitReason.EXCESSIVE_RESOURCE_USAGE to
                    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
                ExitReason.USER_REQUESTED to ApplicationExitInfo.REASON_USER_REQUESTED,
                ExitReason.USER_STOPPED to ApplicationExitInfo.REASON_USER_STOPPED,
                ExitReason.DEPENDENCY_DIED to ApplicationExitInfo.REASON_DEPENDENCY_DIED,
                ExitReason.OTHER to ApplicationExitInfo.REASON_OTHER,
                ExitReason.FREEZER to ApplicationExitInfo.REASON_FREEZER,
                ExitReason.PACKAGE_STATE_CHANGE to ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE,
                ExitReason.PACKAGE_UPDATED to ApplicationExitInfo.REASON_PACKAGE_UPDATED,
            )
        assertEquals(ExitReason.entries.toSet(), android.keys)
        android.forEach { (reason, code) -> assertEquals(code, reason.code, reason.name) }
    }
}
