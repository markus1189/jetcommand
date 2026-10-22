package link.thesymbolic.jetcommand

import android.annotation.SuppressLint
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import link.thesymbolic.jetcommand.ble.BedJetScanner
import link.thesymbolic.jetcommand.ble.GattTransport
import link.thesymbolic.jetcommand.ble.ScannedDevice
import link.thesymbolic.jetcommand.protocol.Reply
import link.thesymbolic.jetcommand.protocol.TransportState
import link.thesymbolic.jetcommand.protocol.toHex

/** Bare transport test bench for the first real-device run; shown only behind RequireBluetoothPermissions. */
@SuppressLint("MissingPermission") // composed only after RequireBluetoothPermissions saw both granted
@Composable
fun DebugScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scanner = remember { BedJetScanner(context) }
    var scanning by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val found = remember { mutableStateMapOf<String, ScannedDevice>() }
    var transport by remember { mutableStateOf<GattTransport?>(null) }
    var lastStatus by remember { mutableStateOf<ByteArray?>(null) }

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
    LaunchedEffect(transport) {
        lastStatus = null
        transport?.notifications?.collect { lastStatus = it }
    }
    DisposableEffect(Unit) { onDispose { transport?.let { t -> scope.launch { t.disconnect() } } } }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val t = transport
        val state by (t?.state ?: remember { MutableStateFlow(TransportState.Disconnected) }).collectAsState()
        Text("Bluetooth: " + if (scanner.isEnabled) "on" else "off")
        error?.let { Text("Error: $it") }
        if (t == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { scanning = !scanning }) { Text(if (scanning) "Stop scan" else "Scan") }
                Row { Checkbox(showAll, { showAll = it }); Text("All devices", Modifier.padding(top = 12.dp)) }
            }
            LazyColumn {
                items(found.values.sortedByDescending { it.rssi }, key = { it.address }) { d ->
                    Text(
                        "${d.name ?: "(no name)"}  ${d.address}  ${d.rssi} dBm",
                        Modifier.fillMaxWidth().clickable {
                            scanning = false
                            val nt = GattTransport(context, d.device)
                            transport = nt
                            scope.launch {
                                try {
                                    nt.connect()
                                } catch (e: Exception) {
                                    error = "connect: ${e.message}"
                                }
                            }
                        }.padding(vertical = 12.dp),
                    )
                    HorizontalDivider()
                }
            }
        } else {
            Text("State: $state")
            Text("MTU: ${(state as? TransportState.Connected)?.mtu ?: "-"}")
            val raw = lastStatus
            Text("Last status: ${raw?.toHex() ?: "-"}", fontFamily = FontFamily.Monospace)
            raw?.let { Reply.parseStatus(it) }?.let { s ->
                Text("Mode ${s.mode.label}, actual ${s.actualTemp}, target ${s.targetTemp}, ambient ${s.ambientTemp}, fan ${s.fanPercent}%")
            }
            Button(onClick = {
                transport = null
                scope.launch { t.disconnect() }
            }) { Text("Disconnect") }
        }
    }
}
