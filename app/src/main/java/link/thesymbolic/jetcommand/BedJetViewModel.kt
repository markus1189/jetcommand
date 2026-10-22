package link.thesymbolic.jetcommand

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import link.thesymbolic.jetcommand.ble.GattTransport
import link.thesymbolic.jetcommand.protocol.BedJetDevice
import link.thesymbolic.jetcommand.protocol.BedJetException
import link.thesymbolic.jetcommand.protocol.BedJetLink
import link.thesymbolic.jetcommand.protocol.BedJetSim
import link.thesymbolic.jetcommand.protocol.BedJetTransport
import link.thesymbolic.jetcommand.protocol.LogLevel
import link.thesymbolic.jetcommand.protocol.SimTransport
import java.time.LocalTime

/** One transport + link + device; [sim] is set for the demo source. */
class BedJetStack(
    val label: String,
    val transport: BedJetTransport,
    val link: BedJetLink,
    val device: BedJetDevice,
    val scope: CoroutineScope,
) {
    val sim get() = transport as? SimTransport
}

/**
 * Owns the current [BedJetStack] across configuration changes. Everything runs on Main.immediate, the
 * single thread [BedJetLink] and [BedJetDevice] require.
 */
class BedJetViewModel(app: Application) : AndroidViewModel(app) {
    private val _stack = MutableStateFlow<BedJetStack?>(null)
    val stack: StateFlow<BedJetStack?> = _stack.asStateFlow()

    val log = mutableStateListOf<String>()
    /** Read-back results by label, for the current stack. */
    val results = mutableStateMapOf<String, String>()
    private var switching: Job? = null

    fun log(level: LogLevel, msg: String) {
        log += "${LocalTime.now().withNano(0)} ${level.name.first()} $msg"
        if (log.size > 100) log.removeRange(0, log.size - 100)
    }

    @SuppressLint("MissingPermission") // only reachable from the scan list, shown behind RequireBluetoothPermissions
    fun connectReal(device: BluetoothDevice, name: String?) =
        switchTo(name ?: device.address) { GattTransport(getApplication(), device) }

    fun connectDemo() = switchTo("Demo BedJet") { SimTransport(BedJetSim(), it) }

    fun disconnect() = switchTo(null) { error("unused") }

    private fun switchTo(label: String?, make: (CoroutineScope) -> BedJetTransport) {
        val previous = switching
        switching = viewModelScope.launch {
            previous?.join()
            _stack.value?.let { teardown(it) }
            _stack.value = null
            results.clear()
            if (label == null) return@launch
            val scope = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]))
            val transport = make(scope)
            val link = BedJetLink(transport, scope, log = ::log)
            val stack = BedJetStack(label, transport, link, BedJetDevice(link, scope), scope)
            _stack.value = stack
            scope.launch { stack.device.notifications.collect { log(LogLevel.WARN, "Notification ${it.code}: ${it.text}") } }
            guard("connect") { link.connect() }
        }
    }

    private suspend fun teardown(stack: BedJetStack) = withContext(NonCancellable) {
        runCatching { stack.link.disconnect() }
        stack.scope.coroutineContext[Job]?.cancelAndJoin()
    }

    /** Runs [block] on the current stack; failures go to the log, never up. */
    fun act(what: String, block: suspend (BedJetStack) -> Unit) {
        val stack = _stack.value ?: return log(LogLevel.ERROR, "$what: no device")
        stack.scope.launch { guard(what) { block(stack) } }
    }

    private suspend fun guard(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: BedJetException) {
            log(LogLevel.ERROR, "$what: ${e.code} ${e.message}")
        } catch (e: Exception) {
            log(LogLevel.ERROR, "$what: ${e::class.simpleName} ${e.message}")
        }
    }

    override fun onCleared() {
        // viewModelScope is already cancelled; a GATT left open would leak until the process dies.
        val stack = _stack.value ?: return
        CoroutineScope(Dispatchers.Main.immediate).launch { teardown(stack) }
    }
}
