package link.thesymbolic.jetcommand

import link.thesymbolic.jetcommand.protocol.core.Step

/** A step placed in time: minutes since the program's start. */
data class Span(val index: Int, val step: Step, val from: Int, val to: Int) {
    val minutes
        get() = to - from
}
