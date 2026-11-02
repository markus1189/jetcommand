package link.thesymbolic.jetcommand

/** The unit [BedJetController.autoConnectOnce] connects to, or null to leave things be. */
fun autoConnectTarget(
    remembered: Source.Device?,
    alreadyTried: Boolean,
    connected: Boolean,
    hasPermission: Boolean,
): Source.Device? = remembered?.takeIf { !alreadyTried && !connected && hasPermission }
