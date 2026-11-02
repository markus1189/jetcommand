package link.thesymbolic.jetcommand.protocol

import link.thesymbolic.jetcommand.protocol.core.Slot

internal fun slot(index: Int) = checkNotNull(Slot.of(index))
