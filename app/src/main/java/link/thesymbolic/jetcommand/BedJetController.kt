package link.thesymbolic.jetcommand

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import link.thesymbolic.jetcommand.ble.GattTransport
import link.thesymbolic.jetcommand.protocol.BedJetDevice
import link.thesymbolic.jetcommand.protocol.BedJetException
import link.thesymbolic.jetcommand.protocol.BedJetLink
import link.thesymbolic.jetcommand.protocol.BedJetSim
import link.thesymbolic.jetcommand.protocol.BedJetTransport
import link.thesymbolic.jetcommand.protocol.LinkState
import link.thesymbolic.jetcommand.protocol.LogLevel
import link.thesymbolic.jetcommand.protocol.Mode
import link.thesymbolic.jetcommand.protocol.NightEvent
import link.thesymbolic.jetcommand.protocol.NightLogStore
import link.thesymbolic.jetcommand.protocol.NightRecorder
import link.thesymbolic.jetcommand.protocol.SimTransport
import link.thesymbolic.jetcommand.protocol.StatusUpdate
import link.thesymbolic.jetcommand.protocol.TappedTransport
import java.time.LocalTime

sealed interface Source {
    val label: String

    data object Demo : Source {
        override val label = "Demo BedJet"
    }

    data class Device(val address: String, val name: String?) : Source {
        override val label get() = name ?: address
    }
}

/** One transport + link + device for [source]; [sim] is set for the demo. */
class BedJetStack(
    val source: Source,
    val transport: BedJetTransport,
    val sim: SimTransport?,
    val link: BedJetLink,
    val device: BedJetDevice,
    val recorder: NightRecorder,
    val scope: CoroutineScope,
) {
    val label get() = source.label
}

/**
 * The process's single owner of the BedJet connection; the UI and [BedJetService] only observe and ask.
 * Everything runs on Main.immediate, the single thread [BedJetLink] and [BedJetDevice] require.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BedJetController(context: Context, private val store: NightLogStore) {
    private val context = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = this.context.getSharedPreferences("jetcommand", Context.MODE_PRIVATE)

    private val _stack = MutableStateFlow<BedJetStack?>(null)
    val stack: StateFlow<BedJetStack?> = _stack.asStateFlow()
    val linkState: StateFlow<LinkState> = _stack.flatMapLatest { it?.link?.state ?: flowOf(LinkState.Disconnected()) }
        .stateIn(scope, SharingStarted.Eagerly, LinkState.Disconnected())
    val status: StateFlow<StatusUpdate?> = _stack.flatMapLatest { it?.link?.status ?: flowOf(null) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val _remembered = MutableStateFlow(loadRemembered())
    /** The last real device connected to, persisted. */
    val remembered: StateFlow<Source.Device?> = _remembered.asStateFlow()

    fun nightLog(limit: Int = 50): Flow<List<NightEvent>> = store.recent(limit)

    fun lifecycle(detail: String, at: Long = System.currentTimeMillis()) {
        scope.launch { store.append(NightEvent(at, NightEvent.Kind.PROCESS, detail)) }
    }

    private var switching: Job? = null
    private var autoConnectTried = false

    init {
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        this.context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) != BluetoothAdapter.STATE_ON) return
                val link = _stack.value?.link ?: return
                log(LogLevel.INFO, "Bluetooth on")
                link.retryNow()
            }
        }, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    fun log(level: LogLevel, msg: String) = _log.update { (it + "${LocalTime.now().withNano(0)} ${level.name.first()} $msg").takeLast(100) }

    fun connectDemo() = switchTo(Source.Demo)

    fun connect(device: Source.Device) = switchTo(device)

    fun disconnect() = switchTo(null)

    /** Once per process, from the UI: reconnects to the remembered device if nothing is connected. */
    fun autoConnectOnce() {
        if (autoConnectTried) return
        autoConnectTried = true
        val device = _remembered.value ?: return
        if (_stack.value != null || !hasConnectPermission()) return
        log(LogLevel.INFO, "Auto-connecting to ${device.label}")
        connect(device)
    }

    fun hasConnectPermission() =
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Must be called while the app is in the foreground: it starts [BedJetService]. */
    private fun switchTo(source: Source?) {
        val previous = switching
        switching = scope.launch {
            previous?.join()
            _stack.value?.let { teardown(it) }
            // Switching sources keeps the old (torn down) stack until the new one exists, so the service doesn't stop.
            if (source == null) {
                _stack.value = null
                return@launch
            }
            if (source is Source.Device) remember(source)
            val stack = build(source)
            _stack.value = stack
            BedJetService.start(context, this@BedJetController)
            guard("connect") { stack.link.connect() }
        }
    }

    private fun build(source: Source): BedJetStack {
        val scope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        val raw: BedJetTransport = when (source) {
            Source.Demo -> SimTransport(BedJetSim(), scope)
            is Source.Device -> {
                val adapter = context.getSystemService(BluetoothManager::class.java).adapter
                GattTransport(context, adapter.getRemoteDevice(source.address))
            }
        }
        lateinit var recorder: NightRecorder
        val transport = TappedTransport(raw) { recorder.command(it) }
        // The user wants the link until they say otherwise: keep trying all night.
        val config = BedJetLink.Config(maxReconnectAttempts = null, retryFailedConnect = true)
        val link = BedJetLink(transport, scope, config, log = ::log)
        recorder = NightRecorder(link, store, scope)
        val device = BedJetDevice(link, scope)
        scope.launch { device.notifications.collect { log(LogLevel.WARN, "Notification ${it.code}: ${it.text}") } }
        return BedJetStack(source, transport, raw as? SimTransport, link, device, recorder, scope)
    }

    private suspend fun teardown(stack: BedJetStack) = withContext(NonCancellable) {
        stack.recorder.userDisconnecting()
        runCatching { stack.link.disconnect() }
        stack.scope.coroutineContext[Job]?.cancelAndJoin()
    }

    /** Runs [block] on the current stack; failures go to the log, never up. */
    fun act(what: String, block: suspend (BedJetStack) -> Unit) {
        val stack = _stack.value ?: return log(LogLevel.ERROR, "$what: no device")
        stack.scope.launch { guard(what) { block(stack) } }
    }

    fun press(what: String, button: Int) = act(what) { it.device.press(button) }

    fun setMode(mode: Mode) = act("mode ${mode.label}") { it.device.setMode(mode) }

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

    // SharedPreferences, not DataStore: two strings, read once synchronously at startup to decide on auto-connect.
    private fun loadRemembered(): Source.Device? {
        val address = prefs.getString(KEY_ADDRESS, null) ?: return null
        return Source.Device(address, prefs.getString(KEY_NAME, null))
    }

    private fun remember(device: Source.Device) {
        prefs.edit().putString(KEY_ADDRESS, device.address).putString(KEY_NAME, device.name).apply()
        _remembered.value = device
    }

    private companion object {
        const val KEY_ADDRESS = "device.address"
        const val KEY_NAME = "device.name"
    }
}
