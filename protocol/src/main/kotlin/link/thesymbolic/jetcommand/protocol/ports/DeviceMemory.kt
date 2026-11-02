package link.thesymbolic.jetcommand.protocol.ports

/** A real unit connected to before; [name] is what the scan reported, if anything. */
data class RememberedDevice(val address: String, val name: String?)

/** The last real unit connected to, kept across processes for auto-connect. */
interface DeviceMemory {
    /** Read synchronously: the auto-connect decision at startup needs it before the first frame. */
    fun load(): RememberedDevice?

    fun save(device: RememberedDevice)
}
