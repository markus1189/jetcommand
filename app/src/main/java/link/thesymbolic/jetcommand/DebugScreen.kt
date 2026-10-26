package link.thesymbolic.jetcommand

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import link.thesymbolic.jetcommand.ble.BedJetScanner
import link.thesymbolic.jetcommand.ble.RequireBluetoothPermissions
import link.thesymbolic.jetcommand.ble.ScannedDevice
import link.thesymbolic.jetcommand.protocol.Characteristic
import link.thesymbolic.jetcommand.protocol.Key
import link.thesymbolic.jetcommand.protocol.LinkState
import link.thesymbolic.jetcommand.protocol.LogLevel
import link.thesymbolic.jetcommand.protocol.NightEvent
import link.thesymbolic.jetcommand.protocol.Notification
import link.thesymbolic.jetcommand.protocol.SettableMode
import link.thesymbolic.jetcommand.protocol.SimTransport
import link.thesymbolic.jetcommand.protocol.Slot
import link.thesymbolic.jetcommand.protocol.Status
import link.thesymbolic.jetcommand.protocol.StatusUpdate
import link.thesymbolic.jetcommand.protocol.Step
import link.thesymbolic.jetcommand.protocol.Timing
import link.thesymbolic.jetcommand.protocol.TransportState
import link.thesymbolic.jetcommand.protocol.fanPercent
import link.thesymbolic.jetcommand.protocol.formatTemp

/** Full-stack test bench: a real BedJet from the scan list, or the demo sim with fault buttons. */
@Composable
fun DebugScreen(vm: BedJetViewModel, modifier: Modifier = Modifier) {
    val stack by vm.stack.collectAsState()
    val log by vm.log.collectAsState()
    val remembered by vm.remembered.collectAsState()
    val nightLog by vm.nightLog.collectAsState()
    val results by vm.results.collectAsState()
    Column(
        modifier.padding(horizontal = 12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val s = stack
        if (s == null) {
            Button(onClick = vm::connectDemo, Modifier.fillMaxWidth()) { Text("Demo BedJet") }
            RequireBluetoothPermissions {
                remembered?.let { d ->
                    Button(onClick = vm::connectRemembered, Modifier.fillMaxWidth()) {
                        Text("Connect ${d.label} (${d.address})")
                    }
                }
                ScanList(vm::connectReal)
            }
        } else {
            Connected(s, vm::disconnect, vm::act, results, vm::readBack, vm::log)
        }
        HorizontalDivider()
        Text("Night log", fontWeight = FontWeight.Bold)
        val zone = remember { ZoneId.systemDefault() }
        val today = LocalDate.now(zone)
        nightLog.forEach {
            Text(
                nightLine(it, zone, today),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 13.sp,
            )
        }
        HorizontalDivider()
        Text("Log", fontWeight = FontWeight.Bold)
        log.asReversed().forEach {
            Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 13.sp)
        }
    }
}

@SuppressLint("MissingPermission") // composed only inside RequireBluetoothPermissions
@Composable
private fun ColumnScope.ScanList(connectReal: (BluetoothDevice, String?) -> Unit) {
    val context = LocalContext.current
    val scanner = remember { BedJetScanner(context) }
    var scanning by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val found = remember { mutableStateMapOf<String, ScannedDevice>() }

    LaunchedEffect(scanning, showAll) {
        if (!scanning) return@LaunchedEffect
        found.clear()
        error = null
        try {
            scanner.scan(showAll).collect { found[it.address] = it }
        } catch (e: CancellationException) {
            throw e // also an IllegalStateException
        } catch (e: IllegalStateException) {
            error = e.message
            scanning = false
        }
    }
    Text("Bluetooth: " + if (scanner.isEnabled) "on" else "off")
    error?.let { Text("Error: $it") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { scanning = !scanning }) { Text(if (scanning) "Stop scan" else "Scan") }
        Row {
            Checkbox(showAll, { showAll = it })
            Text("All devices", Modifier.padding(top = 12.dp))
        }
    }
    found.values
        .sortedByDescending { it.rssi }
        .forEach { d ->
            Text(
                "${d.name ?: "(no name)"}  ${d.address}  ${d.rssi} dBm",
                Modifier.fillMaxWidth()
                    .clickable {
                        scanning = false
                        connectReal(d.device, d.name)
                    }
                    .padding(vertical = 12.dp),
            )
            HorizontalDivider()
        }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Buttons(content: @Composable () -> Unit) =
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { content() }

@Composable
private fun B(label: String, onClick: () -> Unit) =
    OutlinedButton(onClick, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)) {
        Text(label, fontSize = 13.sp)
    }

@Composable
private fun ColumnScope.Connected(
    s: BedJetStack,
    disconnect: () -> Unit,
    act: (String, suspend (BedJetStack) -> Unit) -> Unit,
    results: Map<String, String>,
    readBack: ReadBack,
    log: (LogLevel, String) -> Unit,
) {
    val link by s.link.state.collectAsState()
    val transport by s.transport.state.collectAsState()
    val update by s.link.status.collectAsState()

    LinkHeader(s, link, disconnect) { act("connect") { it.link.connect() } }
    // replySource is a plain var: re-read whenever a status recomposes this.
    Text(connectionLine((transport as? TransportState.Connected)?.mtu, s.link.replySource))
    StatusLines(update)
    Commands(act, results, readBack)
    s.sim?.let { sim -> DemoControls(sim, log) }
}

@Composable
private fun ColumnScope.LinkHeader(
    s: BedJetStack,
    link: LinkState,
    disconnect: () -> Unit,
    connect: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(s.label, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
        B("Disconnect", disconnect)
        if (link is LinkState.Disconnected) B("Connect", connect)
    }
    Text(debugLinkLine(link))
}

@Composable
private fun ColumnScope.StatusLines(u: StatusUpdate?) {
    // The unit notifies ~4/s but the remainder is read every 2 s, so the latest update is almost
    // never the fresh one.
    var freshAt by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(u) { if (u?.extraFresh == true) freshAt = System.currentTimeMillis() }
    // link.status drops repeats of an unchanged status, so the age needs its own clock to keep
    // moving.
    val nowMs by
        produceState(System.currentTimeMillis()) {
            while (true) {
                delay(250)
                value = System.currentTimeMillis()
            }
        }
    statusLines(u?.status, freshAt?.let { nowMs - it }).forEach { Text(it) }
}

@Composable
private fun ColumnScope.Commands(
    act: (String, suspend (BedJetStack) -> Unit) -> Unit,
    results: Map<String, String>,
    readBack: ReadBack,
) {
    Text("Mode", fontWeight = FontWeight.Bold)
    Buttons {
        SettableMode.entries.forEach { m ->
            B(m.mode.label) { act("mode ${m.mode.label}") { it.device.setMode(m) } }
        }
    }
    Buttons {
        B("Fan −") { act("fan down") { it.device.press(Key.FAN_DOWN) } }
        B("Fan +") { act("fan up") { it.device.press(Key.FAN_UP) } }
        B("Temp −") { act("temp down") { it.device.press(Key.TEMP_DOWN_C) } }
        B("Temp +") { act("temp up") { it.device.press(Key.TEMP_UP_C) } }
        B("Sync clock") { act("sync clock") { it.device.syncClock() } }
    }

    Text("Read back", fontWeight = FontWeight.Bold)
    Buttons {
        B("Name") { readBack("Name") { it.device.deviceName() } }
        B("Firmware") { readBack("Firmware") { it.device.firmware() } }
        B("Memories") { readBack("Memories") { it.device.memoryNames().joinToString(" | ") } }
        B("Programs") { readBack("Programs") { programNamesText(it.device.programNames()) } }
        Slot.all.forEach { slot ->
            val label = "Slot ${slot.index}"
            B(label) { readBack(label) { slotText(it.device.programSteps(slot)) } }
        }
    }
    resultLines(results).forEach {
        Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
}

@Composable
private fun ColumnScope.DemoControls(sim: SimTransport, log: (LogLevel, String) -> Unit) {
    var dropping by remember(sim) { mutableStateOf(false) }
    Text("Demo faults", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
    Buttons {
        B("Lose link") {
            log(LogLevel.INFO, "Demo: link lost")
            sim.loseLink()
        }
        B(if (dropping) "Notifications: dropped" else "Notifications: on") {
            dropping = !dropping
            sim.dropNotifications = if (dropping) Int.MAX_VALUE else 0
        }
        B("Fail next connect (133)") {
            log(LogLevel.INFO, "Demo: next connect fails with 133")
            sim.inject(SimTransport.Fault.Fail(133)) { it == SimTransport.Op.Connect }
        }
        B("Skip 30 min") {
            sim.sim.skipAhead(30.0)
            sim.notifyStatus()
        }
    }
}

private typealias ReadBack = (String, suspend (BedJetStack) -> String) -> Unit

/**
 * Rows from another day than [today] carry their date: the list spans days, and reads newest first.
 */
internal fun nightLine(e: NightEvent, zone: ZoneId, today: LocalDate): String {
    val date = Instant.ofEpochMilli(e.at).atZone(zone).toLocalDate()
    val day = if (date == today) "" else "${pad2(date.monthValue)}-${pad2(date.dayOfMonth)} "
    return "$day${hhmmss(e.at, zone)} ${e.kind} ${e.detail}"
}

internal fun connectionLine(mtu: Int?, replySource: Characteristic?): String =
    "MTU: ${mtu ?: "-"}   Replies from: ${replySource?.uuid?.drop(4)?.take(4) ?: "-"}"

internal fun debugLinkLine(link: LinkState): String =
    "Link: " +
        when (link) {
            LinkState.Ready -> "Ready"
            is LinkState.Connecting ->
                if (link.reconnectAttempt > 0) "Reconnecting (attempt ${link.reconnectAttempt})"
                else "Connecting"
            is LinkState.Disconnected ->
                if (link.gaveUp) "Disconnected (gave up)" else "Disconnected"
        }

/**
 * [extraAgeMs]: since the last fresh extra read, null if none this stack. Up to 250 ms negative:
 * the screen's clock ticks slower than extras arrive.
 */
internal fun statusLines(st: Status?, extraAgeMs: Long?): List<String> {
    if (st == null) return listOf("Status: -")
    val x = st.extra
    val r = st.remainingSeconds
    val extraAge =
        extraAgeMs?.let { "%.1f s old".format(Locale.ROOT, it.coerceAtLeast(0) / 1000.0) }
            ?: "never read"
    val notification =
        x?.notification
            ?.takeIf { it != Notification.None }
            ?.let { "\nNotification ${it.code}: ${it.text}" }
            .orEmpty()
    return listOf(
        "Mode ${st.mode.label}   actual ${formatTemp(st.actualTemp)}   target ${formatTemp(st.targetTemp)}   fan ${st.fanPercent}%",
        "Remaining ${hoursMinutes(r)}:${pad2(r % 60)}   ambient ${formatTemp(st.ambientTemp)}",
        "Program step ${x?.programStep ?: "-"}   extra $extraAge$notification",
    )
}

internal fun programNamesText(names: List<String?>): String =
    names.joinToString(" | ") { it ?: "-" }

internal fun slotText(steps: List<Step>): String =
    steps.joinToString("; ", transform = ::stepText).ifEmpty { "(empty)" }

internal fun resultLines(results: Map<String, String>): List<String> =
    results.entries.sortedBy { it.key }.map { (k, v) -> "$k: $v" }

internal fun stepText(s: Step): String {
    val (until, h, m) =
        when (val t = s.timing) {
            is Timing.For -> Triple("", t.hours, t.minutes)
            is Timing.Until -> Triple("until ", t.hour, t.minute)
        }
    return "${s.mode.label} ${fanPercent(s.fan)}% ${formatTemp(s.temp)} $until$h:${pad2(m)}"
}
