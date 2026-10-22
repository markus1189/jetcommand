package link.thesymbolic.jetcommand

import android.annotation.SuppressLint
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import link.thesymbolic.jetcommand.ble.BedJetScanner
import link.thesymbolic.jetcommand.ble.RequireBluetoothPermissions
import link.thesymbolic.jetcommand.ble.ScannedDevice
import link.thesymbolic.jetcommand.protocol.Button as Btn
import link.thesymbolic.jetcommand.protocol.LinkState
import link.thesymbolic.jetcommand.protocol.Limits
import link.thesymbolic.jetcommand.protocol.LogLevel
import link.thesymbolic.jetcommand.protocol.Mode
import link.thesymbolic.jetcommand.protocol.Notification
import link.thesymbolic.jetcommand.protocol.SimTransport
import link.thesymbolic.jetcommand.protocol.Step
import link.thesymbolic.jetcommand.protocol.TransportState
import link.thesymbolic.jetcommand.protocol.fanPercent
import link.thesymbolic.jetcommand.protocol.formatTemp

/** Full-stack test bench: a real BedJet from the scan list, or the demo sim with fault buttons. */
@Composable
fun DebugScreen(vm: BedJetViewModel) {
    val stack by vm.stack.collectAsState()
    Column(
        Modifier.padding(horizontal = 12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val s = stack
        if (s == null) {
            Button(onClick = vm::connectDemo, Modifier.fillMaxWidth()) { Text("Demo BedJet") }
            RequireBluetoothPermissions { ScanList(vm) }
        } else {
            Connected(vm, s)
        }
        HorizontalDivider()
        Text("Log", fontWeight = FontWeight.Bold)
        vm.log.asReversed().forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 13.sp) }
    }
}

@SuppressLint("MissingPermission") // composed only inside RequireBluetoothPermissions
@Composable
private fun ScanList(vm: BedJetViewModel) {
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
        Row { Checkbox(showAll, { showAll = it }); Text("All devices", Modifier.padding(top = 12.dp)) }
    }
    found.values.sortedByDescending { it.rssi }.forEach { d ->
        Text(
            "${d.name ?: "(no name)"}  ${d.address}  ${d.rssi} dBm",
            Modifier.fillMaxWidth().clickable {
                scanning = false
                vm.connectReal(d.device, d.name)
            }.padding(vertical = 12.dp),
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
    OutlinedButton(onClick, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)) { Text(label, fontSize = 13.sp) }

@Composable
private fun Connected(vm: BedJetViewModel, s: BedJetStack) {
    val link by s.link.state.collectAsState()
    val transport by s.transport.state.collectAsState()
    val update by s.link.status.collectAsState()

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(s.label, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
        B("Disconnect", vm::disconnect)
        if (link is LinkState.Disconnected) B("Connect") { vm.act("connect") { it.link.connect() } }
    }
    Text(
        "Link: " + when (val l = link) {
            LinkState.Ready -> "Ready"
            is LinkState.Connecting -> if (l.reconnectAttempt > 0) "Reconnecting (attempt ${l.reconnectAttempt})" else "Connecting"
            is LinkState.Disconnected -> if (l.gaveUp) "Disconnected (gave up)" else "Disconnected"
        },
    )
    // replySource is a plain var: re-read whenever a status recomposes this.
    Text("MTU: ${(transport as? TransportState.Connected)?.mtu ?: "-"}   Replies from: ${s.link.replySource?.uuid?.substring(4, 8) ?: "-"}")
    val u = update
    // The unit notifies ~4/s but the remainder is read every 2 s, so the latest update is almost never the fresh one.
    var freshAt by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(u) { if (u?.extraFresh == true) freshAt = System.currentTimeMillis() }
    // link.status drops repeats of an unchanged status, so the age needs its own clock to keep moving.
    val nowMs by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(250)
            value = System.currentTimeMillis()
        }
    }
    if (u == null) Text("Status: -")
    else {
        val st = u.status
        val x = st.extra
        Text("Mode ${st.mode.label}   actual ${formatTemp(st.actualTemp)}   target ${formatTemp(st.targetTemp)}   fan ${st.fanPercent}%")
        Text("Remaining ${st.remainingSeconds / 3600}:%02d:%02d   ambient ${formatTemp(st.ambientTemp)}".format(st.remainingSeconds / 60 % 60, st.remainingSeconds % 60))
        Text(
            "Program step ${x?.programStep ?: "-"}   extra ${freshAt?.let { "%.1f s old".format((nowMs - it) / 1000.0) } ?: "never read"}" +
                (x?.notification?.takeIf { it != 0 }?.let { "\nNotification $it: ${Notification.TEXT[it] ?: "?"}" } ?: ""),
        )
    }

    Text("Mode", fontWeight = FontWeight.Bold)
    Buttons {
        listOf(Mode.STANDBY, Mode.HEAT, Mode.COOL, Mode.DRY, Mode.TURBO, Mode.EXT_HEAT).forEach { m ->
            B(m.label) { vm.act("mode ${m.label}") { it.device.setMode(m) } }
        }
    }
    Buttons {
        B("Fan −") { vm.act("fan down") { it.device.press(Btn.FAN_DOWN) } }
        B("Fan +") { vm.act("fan up") { it.device.press(Btn.FAN_UP) } }
        B("Temp −") { vm.act("temp down") { it.device.press(Btn.TEMP_DOWN_C) } }
        B("Temp +") { vm.act("temp up") { it.device.press(Btn.TEMP_UP_C) } }
        B("Sync clock") { vm.act("sync clock") { it.device.syncClock() } }
    }

    Text("Read back", fontWeight = FontWeight.Bold)
    Buttons {
        B("Name") { vm.act("name") { vm.results["Name"] = it.device.deviceName() } }
        B("Firmware") { vm.act("firmware") { vm.results["Firmware"] = it.device.firmware() } }
        B("Memories") { vm.act("memories") { vm.results["Memories"] = it.device.memoryNames().joinToString(" | ") } }
        B("Programs") { vm.act("programs") { vm.results["Programs"] = it.device.programNames().joinToString(" | ") { n -> n ?: "-" } } }
        (0 until Limits.SLOTS).forEach { slot ->
            B("Slot $slot") {
                vm.act("slot $slot") { vm.results["Slot $slot"] = it.device.programSteps(slot).joinToString("; ", transform = ::stepText).ifEmpty { "(empty)" } }
            }
        }
    }
    vm.results.entries.sortedBy { it.key }.forEach { (k, v) -> Text("$k: $v", fontFamily = FontFamily.Monospace, fontSize = 12.sp) }

    s.sim?.let { sim -> DemoControls(vm, sim) }
}

@Composable
private fun DemoControls(vm: BedJetViewModel, sim: SimTransport) {
    var dropping by remember(sim) { mutableStateOf(false) }
    Text("Demo faults", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
    Buttons {
        B("Lose link") {
            vm.log(LogLevel.INFO, "Demo: link lost")
            sim.loseLink()
        }
        B(if (dropping) "Notifications: dropped" else "Notifications: on") {
            dropping = !dropping
            sim.dropNotifications = if (dropping) Int.MAX_VALUE else 0
        }
        B("Fail next connect (133)") {
            vm.log(LogLevel.INFO, "Demo: next connect fails with 133")
            sim.inject(SimTransport.Fault.Fail(133)) { it == SimTransport.Op.Connect }
        }
        B("Skip 30 min") {
            sim.sim.skipAhead(30.0)
            sim.notifyStatus()
        }
    }
}

private fun stepText(s: Step) =
    "${s.mode.label} ${fanPercent(s.fan)}% ${formatTemp(s.temp)} ${if (s.clock) "until " else ""}${s.hours}:${s.minutes.toString().padStart(2, '0')}"
