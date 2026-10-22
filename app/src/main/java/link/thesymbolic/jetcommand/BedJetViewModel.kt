package link.thesymbolic.jetcommand

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import link.thesymbolic.jetcommand.protocol.LogLevel

/** The debug screen's view of [BedJetController]; the connection lives there, not here. */
class BedJetViewModel(app: Application) : AndroidViewModel(app) {
    private val controller = app.controller
    val stack = controller.stack
    val log = controller.log
    val remembered = controller.remembered
    val nightLog = controller.nightLog(50).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Read-back results by label, for the current stack. */
    val results = mutableStateMapOf<String, String>()

    init {
        viewModelScope.launch { stack.collect { results.clear() } }
    }

    fun log(level: LogLevel, msg: String) = controller.log(level, msg)

    @SuppressLint("MissingPermission") // only reachable from the scan list, shown behind RequireBluetoothPermissions
    fun connectReal(device: BluetoothDevice, name: String?) = controller.connect(Source.Device(device.address, name))

    fun connectRemembered() = remembered.value?.let(controller::connect)

    fun connectDemo() = controller.connectDemo()

    fun disconnect() = controller.disconnect()

    fun autoConnectOnce() = controller.autoConnectOnce()

    fun act(what: String, block: suspend (BedJetStack) -> Unit) = controller.act(what, block)
}
