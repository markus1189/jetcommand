package link.thesymbolic.jetcommand.protocol.core

data class Program(val name: String, val steps: List<Step>)

data class Memory(val name: String, val mode: Mode, val fan: Int, val temp: Int)
