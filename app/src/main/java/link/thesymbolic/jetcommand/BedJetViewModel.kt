package link.thesymbolic.jetcommand

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import link.thesymbolic.jetcommand.protocol.CommandOrigin
import link.thesymbolic.jetcommand.protocol.CommandRequest
import link.thesymbolic.jetcommand.protocol.LogLevel
import link.thesymbolic.jetcommand.protocol.SettableMode
import link.thesymbolic.jetcommand.protocol.Slot
import link.thesymbolic.jetcommand.protocol.UserCommand

/** The UI's view of [BedJetController]; the connection lives there, not here. */
class BedJetViewModel(app: Application) : AndroidViewModel(app) {
    private val controller = app.controller
    val stack = controller.stack
    val log = controller.log
    val remembered = controller.remembered
    val linkState = controller.linkState
    val status = controller.status
    val nightLog =
        controller
            .nightLog(50)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _results = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Read-back results by label, for the current stack. */
    val results: StateFlow<Map<String, String>> = _results.asStateFlow()

    private val _programs = MutableStateFlow<List<String?>>(emptyList())
    /** The unit's program slots by index, null where empty; read once per stack. */
    val programs: StateFlow<List<String?>> = _programs.asStateFlow()

    init {
        viewModelScope.launch {
            stack.collect {
                _results.value = emptyMap()
                _programs.value = emptyList()
            }
        }
    }

    fun log(level: LogLevel, msg: String) = controller.log(level, msg)

    // only reachable from the scan list, shown behind RequireBluetoothPermissions
    @SuppressLint("MissingPermission")
    fun connectReal(device: BluetoothDevice, name: String?) =
        controller.connect(Source.Device(device.address, name))

    fun connectRemembered() = remembered.value?.let(controller::connect)

    fun connectDemo() = controller.connectDemo()

    fun disconnect() = controller.disconnect()

    fun autoConnectOnce() = controller.autoConnectOnce()

    fun setMode(mode: SettableMode) = execute(UserCommand.SetMode(mode))

    fun setTemperature(halfC: Int) = request(CommandRequest.temperature(halfC))

    fun setFan(step: Int) = request(CommandRequest.fan(step))

    fun setRuntime(minutes: Int) = request(CommandRequest.runtime(minutes))

    fun loadPrograms() = act("programs") { _programs.value = it.device.programNames() }

    /** Runs [read] on the unit and shows its text under [label] in [results]. */
    fun readBack(label: String, read: suspend (BedJetStack) -> String) =
        act(label.lowercase()) { stack ->
            val text = read(stack)
            _results.update { it + (label to text) }
        }

    fun startProgram(slot: Slot) = execute(UserCommand.StartProgram(slot))

    private fun execute(command: UserCommand) = request(CommandRequest.Valid(command))

    private fun request(request: CommandRequest) = controller.execute(request, CommandOrigin.APP)

    fun hasConnectPermission() = controller.hasConnectPermission()

    fun act(what: String, block: suspend (BedJetStack) -> Unit) = controller.act(what, block)
}
