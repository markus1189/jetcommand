package link.thesymbolic.jetcommand

import link.thesymbolic.jetcommand.protocol.core.Mode
import link.thesymbolic.jetcommand.protocol.core.Notification
import link.thesymbolic.jetcommand.protocol.core.Status
import link.thesymbolic.jetcommand.protocol.core.StatusExtra

fun status(
    mode: Mode = Mode.HEAT,
    remainingSeconds: Int = 3_600,
    actualTemp: Int = 60,
    targetTemp: Int = 70,
    fanStep: Int = 9,
    minTemp: Int = 0,
    maxTemp: Int = 0,
    ambientTemp: Int = 44,
    programStep: Int? = null,
    notification: Notification = Notification.None,
) =
    Status(
        partial = false,
        length = 20,
        remainingSeconds = remainingSeconds,
        actualTemp = actualTemp,
        targetTemp = targetTemp,
        mode = mode,
        fanStep = fanStep,
        maxRuntimeMinutes = 600,
        minTemp = minTemp,
        maxTemp = maxTemp,
        turboTime = emptyList(),
        ambientTemp = ambientTemp,
        shutdownReason = 0,
        dualZone = null,
        updatePhase = null,
        extra =
            programStep?.let {
                StatusExtra(
                    flags = 0,
                    connTestPassed = true,
                    ledEnabled = true,
                    unitsSetUp = true,
                    beepsMuted = false,
                    programStep = it,
                    notification = notification,
                )
            },
    )
