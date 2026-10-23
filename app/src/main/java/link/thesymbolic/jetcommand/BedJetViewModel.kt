package link.thesymbolic.jetcommand

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import link.thesymbolic.jetcommand.protocol.LogLevel
import link.thesymbolic.jetcommand.protocol.Mode
import link.thesymbolic.jetcommand.protocol.fanPercent
import link.thesymbolic.jetcommand.protocol.formatTemp

/** The UI's view of [BedJetController]; the connection lives there, not here. */
class BedJetViewModel(private val app: Application) : AndroidViewModel(app) {
    private val controller = app.controller
    val stack = controller.stack
    val log = controller.log
    val remembered = controller.remembered
    val linkState = controller.linkState
    val status = controller.status
    val nightLog = controller.nightLog(50).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Read-back results by label, for the current stack. */
    val results = mutableStateMapOf<String, String>()

    /** The unit's program slots by index, null where empty; read once per stack. */
    var programs by mutableStateOf<List<String?>>(emptyList())
        private set

    init {
        viewModelScope.launch {
            stack.collect {
                results.clear()
                programs = emptyList()
            }
        }
    }

    fun log(level: LogLevel, msg: String) = controller.log(level, msg)

    @SuppressLint("MissingPermission") // only reachable from the scan list, shown behind RequireBluetoothPermissions
    fun connectReal(device: BluetoothDevice, name: String?) = controller.connect(Source.Device(device.address, name))

    fun connectRemembered() = remembered.value?.let(controller::connect)

    fun connectDemo() = controller.connectDemo()

    fun disconnect() = controller.disconnect()

    fun autoConnectOnce() = controller.autoConnectOnce()

    fun press(what: String, button: Int) = controller.press(what, button)

    fun setMode(mode: Mode) = controller.setMode(mode)

    fun setTemperature(halfC: Int) = act("temp ${formatTemp(halfC)}") { it.device.setTemperature(halfC) }

    fun setFan(step: Int) = act("fan ${fanPercent(step)} %") { it.device.setFan(step) }

    fun loadPrograms() = act("programs") { programs = it.device.programNames() }

    fun startProgram(slot: Int) = act("start program ${slot + 1}") {
        it.device.startProgram(slot)
        controller.deviceId.value?.let { id -> app.library.noteStarted(id, slot) }
    }

    fun hasConnectPermission() = controller.hasConnectPermission()

    fun act(what: String, block: suspend (BedJetStack) -> Unit) = controller.act(what, block)
}
