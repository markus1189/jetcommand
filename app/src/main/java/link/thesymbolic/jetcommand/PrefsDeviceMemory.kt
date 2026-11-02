package link.thesymbolic.jetcommand

import android.content.Context
import androidx.core.content.edit
import link.thesymbolic.jetcommand.protocol.DeviceMemory
import link.thesymbolic.jetcommand.protocol.RememberedDevice

// SharedPreferences, not DataStore: two strings, read once synchronously at startup. File and keys
// are those of earlier installs, which keep their remembered unit.
class PrefsDeviceMemory(context: Context) : DeviceMemory {
    private val prefs = context.getSharedPreferences("jetcommand", Context.MODE_PRIVATE)

    override fun load(): RememberedDevice? {
        val address = prefs.getString(KEY_ADDRESS, null) ?: return null
        return RememberedDevice(address, prefs.getString(KEY_NAME, null))
    }

    override fun save(device: RememberedDevice) = prefs.edit {
        putString(KEY_ADDRESS, device.address)
        putString(KEY_NAME, device.name)
    }

    private companion object {
        const val KEY_ADDRESS = "device.address"
        const val KEY_NAME = "device.name"
    }
}
