package link.thesymbolic.jetcommand

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.core.content.edit
import java.time.LocalTime
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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import link.thesymbolic.jetcommand.ble.GattTransport
import link.thesymbolic.jetcommand.protocol.BedJetDevice
import link.thesymbolic.jetcommand.protocol.BedJetException
import link.thesymbolic.jetcommand.protocol.BedJetLink
import link.thesymbolic.jetcommand.protocol.BedJetSim
import link.thesymbolic.jetcommand.protocol.BedJetTransport
import link.thesymbolic.jetcommand.protocol.CommandOrigin
import link.thesymbolic.jetcommand.protocol.CommandRequest
import link.thesymbolic.jetcommand.protocol.CommandSnapshot
import link.thesymbolic.jetcommand.protocol.LinkState
import link.thesymbolic.jetcommand.protocol.LogLevel
import link.thesymbolic.jetcommand.protocol.Mode
import link.thesymbolic.jetcommand.protocol.NightEvent
import link.thesymbolic.jetcommand.protocol.NightLogStore
import link.thesymbolic.jetcommand.protocol.NightRecorder
import link.thesymbolic.jetcommand.protocol.Plan
import link.thesymbolic.jetcommand.protocol.ProcessEvent
import link.thesymbolic.jetcommand.protocol.Program
import link.thesymbolic.jetcommand.protocol.ProgramLibrary
import link.thesymbolic.jetcommand.protocol.READY_TIMEOUT_MS
import link.thesymbolic.jetcommand.protocol.RunOutcome
import link.thesymbolic.jetcommand.protocol.SimTransport
import link.thesymbolic.jetcommand.protocol.StartOutcome
import link.thesymbolic.jetcommand.protocol.StatusUpdate
import link.thesymbolic.jetcommand.protocol.Step
import link.thesymbolic.jetcommand.protocol.TappedTransport
import link.thesymbolic.jetcommand.protocol.Timing
import link.thesymbolic.jetcommand.protocol.UserCommand
import link.thesymbolic.jetcommand.protocol.attempt
import link.thesymbolic.jetcommand.protocol.plan

sealed interface Source {
    val label: String

    data object Demo : Source {
        override val label = "Demo BedJet"
    }

    data class Device(val address: String, val name: String?) : Source {
        override val label
            get() = name ?: address
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
    val label
        get() = source.label
}

/**
 * The process's single owner of the BedJet connection; the UI and [BedJetService] only observe and
 * ask. Everything runs on Main.immediate, the single thread [BedJetLink] and [BedJetDevice]
 * require.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BedJetController(
    context: Context,
    private val store: NightLogStore,
    private val library: ProgramLibrary,
) {
    private val context = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = this.context.getSharedPreferences("jetcommand", Context.MODE_PRIVATE)

    private val _stack = MutableStateFlow<BedJetStack?>(null)
    val stack: StateFlow<BedJetStack?> = _stack.asStateFlow()
    val linkState: StateFlow<LinkState> =
        _stack
            .flatMapLatest { it?.link?.state ?: flowOf(LinkState.Disconnected()) }
            .stateIn(scope, SharingStarted.Eagerly, LinkState.Disconnected())
    val status: StateFlow<StatusUpdate?> =
        _stack
            .flatMapLatest { it?.link?.status ?: flowOf(null) }
            .stateIn(scope, SharingStarted.Eagerly, null)
    /** The [ProgramLibrary] device key of the current stack. */
    val deviceId: StateFlow<String?> =
        _stack.map { it?.source?.let(::idOf) }.stateIn(scope, SharingStarted.Eagerly, null)

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val _remembered = MutableStateFlow(loadRemembered())
    /** The last real device connected to, persisted. */
    val remembered: StateFlow<Source.Device?> = _remembered.asStateFlow()

    fun nightLog(limit: Int = 50): Flow<List<NightEvent>> = store.recent(limit)

    fun lifecycle(
        event: ProcessEvent,
        at: Long = System.currentTimeMillis(),
    ) {
        scope.launch { store.append(NightEvent.process(at, event)) }
    }

    /** For the service's notes; the text is stored as given. */
    fun lifecycle(detail: String, at: Long = System.currentTimeMillis()) =
        lifecycle(ProcessEvent.parse(detail), at)

    private var switching: Job? = null
    private var autoConnectTried = false

    init {
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        // Ends what [ProgramLibrary.started] knows: whatever runs next may have been started
        // elsewhere.
        scope.launch {
            status.collect {
                if (it != null && it.extraFresh && it.status.extra?.programStep == 0)
                    library.clearStarted()
            }
        }
        this.context.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    if (
                        intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) !=
                            BluetoothAdapter.STATE_ON
                    )
                        return
                    val link = _stack.value?.link ?: return
                    log(LogLevel.INFO, "Bluetooth on")
                    link.retryNow()
                }
            },
            filter,
            // Inlined API-33 constant; earlier releases ignore the flag, and STATE_CHANGED is a
            // protected broadcast anyway.
            @SuppressLint("InlinedApi") Context.RECEIVER_NOT_EXPORTED,
        )
    }

    fun log(level: LogLevel, msg: String) = _log.update {
        (it + "${LocalTime.now().withNano(0)} ${level.name.first()} $msg").takeLast(100)
    }

    fun connectDemo() = switchTo(Source.Demo)

    fun connect(device: Source.Device) = switchTo(device)

    fun disconnect() = switchTo(null)

    /**
     * Once per process and once after each give-up, from the UI: reconnects to the remembered
     * device if nothing is connected.
     */
    fun autoConnectOnce() {
        if (autoConnectTried) return
        autoConnectTried = true
        val device = _remembered.value ?: return
        if (_stack.value != null || !hasConnectPermission()) return
        log(LogLevel.INFO, "Auto-connecting to ${device.label}")
        connect(device)
    }

    fun hasConnectPermission() =
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /** Must be called while the app is in the foreground: it starts [BedJetService]. */
    private fun switchTo(source: Source?) {
        val previous = switching
        switching = scope.launch {
            previous?.join()
            _stack.value?.let { teardown(it) }
            // Switching sources keeps the old (torn down) stack until the new one exists, so the
            // service doesn't stop.
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
        val scope =
            CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        val raw: BedJetTransport =
            when (source) {
                Source.Demo -> SimTransport(BedJetSim(slots = demoSlots()), scope)
                is Source.Device -> {
                    val adapter = context.getSystemService(BluetoothManager::class.java).adapter
                    GattTransport(context, adapter.getRemoteDevice(source.address))
                }
            }
        lateinit var recorder: NightRecorder
        val transport = TappedTransport(raw) { recorder.command(it) }
        // Night 1's status-8 drops happened in range, yet the default 60 s cap plus 20 s connect
        // timeouts left a 358 s gap; 10 s keeps it under ~30 s. The BedJet's BLE stays on in
        // standby, so only leaving the room ends a link: after 15 min down, let go for the day.
        val config =
            BedJetLink.Config(
                maxReconnectAttempts = null,
                giveUpAfterDownMs = 15 * 60_000L,
                retryFailedConnect = true,
                reconnectMaxMs = 10_000,
            )
        val link = BedJetLink(transport, scope, config, log = ::log)
        recorder = NightRecorder(link, store, scope)
        val device = BedJetDevice(link, scope)
        scope.launch {
            device.notifications.collect {
                log(LogLevel.WARN, "Notification ${it.code}: ${it.text}")
            }
        }
        val stack =
            BedJetStack(source, transport, raw as? SimTransport, link, device, recorder, scope)
        refreshOnReady(stack)
        disconnectOnGiveUp(stack)
        return stack
    }

    /**
     * Re-reads the slots on every transition to Ready; a Ready while a refresh still runs doesn't
     * start another.
     */
    private fun refreshOnReady(stack: BedJetStack) {
        var running: Job? = null
        stack.scope.launch {
            // StateFlow is distinct, so each Ready emission is a transition.
            stack.link.state
                .filter { it == LinkState.Ready }
                .collect {
                    if (running?.isActive == true) return@collect
                    running =
                        stack.scope.launch {
                            guard("library refresh") {
                                library.refresh(idOf(stack.source), stack.device)
                            }
                        }
                }
        }
    }

    /** Drops the stack, and with it [BedJetService], once the link stops reconnecting. */
    private fun disconnectOnGiveUp(stack: BedJetStack) {
        stack.scope.launch {
            stack.link.state.first { it == LinkState.Disconnected(gaveUp = true) }
            if (_stack.value === stack) {
                log(LogLevel.INFO, "Link gave up: disconnecting until the app is opened again")
                autoConnectTried = false
                disconnect()
            }
        }
    }

    private suspend fun teardown(stack: BedJetStack) =
        withContext(NonCancellable) {
            stack.recorder.userDisconnecting()
            attempt(onError = { log(LogLevel.WARN, "disconnect: ${it.message}") }) {
                stack.link.disconnect()
            }
            stack.scope.coroutineContext[Job]?.cancelAndJoin()
        }

    /** Runs [block] on the current stack; failures go to the log, never up. */
    fun act(what: String, block: suspend (BedJetStack) -> Unit) {
        val stack = _stack.value ?: return log(LogLevel.ERROR, "$what: no device")
        stack.scope.launch { guard(what) { block(stack) } }
    }

    /**
     * The one way commands reach the unit: [plan] decides, this interprets. From [origin]s other
     * than APP it may connect to the remembered device first, so call it while in the foreground.
     */
    fun execute(request: CommandRequest, origin: CommandOrigin) {
        val snapshot =
            CommandSnapshot(
                connected = _stack.value != null,
                demo = _stack.value?.source == Source.Demo,
                // Not [linkState]: that trails a stack switch by a dispatch.
                link = _stack.value?.link?.state?.value ?: LinkState.Disconnected(),
                remembered = _remembered.value != null,
                hasConnectPermission = hasConnectPermission(),
            )
        when (val p = plan(request, origin, snapshot)) {
            is Plan.Rejected -> {
                log(LogLevel.WARN, "${p.label}: ${p.reason}")
                if (p.report) report(p.label, RunOutcome.Failed(p.reason))
            }
            is Plan.Run -> interpret(p)
        }
    }

    private fun interpret(p: Plan.Run) {
        // switchTo runs later: until then the old stack (the demo, say) is current and may be
        // Ready.
        val target =
            when (p) {
                is Plan.Run.Now -> {
                    val stack = _stack.value ?: return failed(p, "no device")
                    runOn(stack, p)
                    return
                }
                is Plan.Run.WhenReady -> if (p.connect) _remembered.value?.also(::connect) else null
            }
        scope.launch {
            val ready =
                withTimeoutOrNull(READY_TIMEOUT_MS) {
                    _stack
                        .flatMapLatest { s ->
                            s?.takeIf { target == null || it.source == target }
                                ?.link
                                ?.state
                                ?.map { state -> s.takeIf { state == LinkState.Ready } }
                                ?: flowOf(null)
                        }
                        .filterNotNull()
                        .first()
                }
            if (ready == null) failed(p, "couldn't reach the BedJet") else runOn(ready, p)
        }
    }

    private fun runOn(stack: BedJetStack, p: Plan.Run) =
        stack.scope.launch {
            val outcome = guarded(p.label) { perform(stack, p.command) }
            if (p.report) report(p.label, outcome)
        }

    private fun failed(p: Plan.Run, reason: String) {
        log(LogLevel.ERROR, "${p.label}: $reason")
        if (p.report) report(p.label, RunOutcome.Failed(reason))
    }

    private suspend fun perform(stack: BedJetStack, command: UserCommand): RunOutcome {
        val device = stack.device
        when (command) {
            is UserCommand.SetMode -> device.setMode(command.mode)
            is UserCommand.SetTemperature -> device.setTemperature(command.target.halfC)
            is UserCommand.SetFan -> device.setFan(command.fan.step)
            is UserCommand.StartProgram -> {
                val started = device.startProgram(command.slot)
                if (started is StartOutcome.Failed) return RunOutcome.Failed(started.reason.text)
                library.noteStarted(idOf(stack.source), command.slot)
            }
            is UserCommand.Press -> device.press(command.key)
            is UserCommand.SetRuntime -> device.setRuntime(command.autoOff.minutes)
        }
        return RunOutcome.Ran
    }

    /** Outcome of a command nobody watched run. */
    private fun report(label: String, outcome: RunOutcome) {
        val text =
            when (outcome) {
                RunOutcome.Ran -> "$label ✓"
                is RunOutcome.Failed -> "$label: ${outcome.error}"
            }
        Toast.makeText(context, "BedJet: $text", Toast.LENGTH_SHORT).show()
        if (outcome is RunOutcome.Failed) ShortcutAlert.post(context, text)
        scope.launch {
            store.append(NightEvent.shortcut(System.currentTimeMillis(), label, outcome))
        }
    }

    // Catches everything: a failed user action is logged, never a crash. Not a
    // CoroutineExceptionHandler, because "connect" runs inline in the switching job that the next
    // switch joins.
    @Suppress("TooGenericExceptionCaught")
    /** [block]'s outcome, a throw turned into [RunOutcome.Failed]; failures are logged. */
    private suspend fun guarded(what: String, block: suspend () -> RunOutcome): RunOutcome {
        val error =
            try {
                when (val outcome = block()) {
                    RunOutcome.Ran -> return outcome
                    is RunOutcome.Failed -> outcome.error
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: BedJetException) {
                "${e.code} ${e.message}"
            } catch (e: Exception) {
                "${e::class.simpleName} ${e.message}"
            }
        log(LogLevel.ERROR, "$what: $error")
        return RunOutcome.Failed(error)
    }

    private suspend fun guard(what: String, block: suspend () -> Unit) {
        guarded(what) {
            block()
            RunOutcome.Ran
        }
    }

    // SharedPreferences, not DataStore: two strings, read once synchronously at startup to decide
    // on auto-connect.
    private fun loadRemembered(): Source.Device? {
        val address = prefs.getString(KEY_ADDRESS, null) ?: return null
        return Source.Device(address, prefs.getString(KEY_NAME, null))
    }

    private fun remember(device: Source.Device) {
        prefs.edit {
            putString(KEY_ADDRESS, device.address)
            putString(KEY_NAME, device.name)
        }
        _remembered.value = device
    }

    companion object {
        const val DEMO_ID = "demo"

        /** Two programs to keep or run, one empty slot. */
        fun demoSlots() =
            listOf(
                Program(
                    "Cool down",
                    listOf(
                        Step(Mode.TURBO, 19, 86, Timing.For(0, 5)),
                        Step(Mode.COOL, 7, 40, Timing.For(6, 0)),
                    ),
                ),
                Program(
                    "Twostep",
                    listOf(
                        Step(Mode.HEAT, 9, 70, Timing.For(0, 30)),
                        Step(Mode.EXT_HEAT, 5, 62, Timing.For(2, 0)),
                    ),
                ),
                null,
            )

        fun idOf(source: Source) = if (source is Source.Device) source.address else DEMO_ID

        private const val KEY_ADDRESS = "device.address"
        private const val KEY_NAME = "device.name"
    }
}
