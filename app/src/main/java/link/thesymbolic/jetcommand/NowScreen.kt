package link.thesymbolic.jetcommand

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import link.thesymbolic.jetcommand.protocol.Button as Btn
import link.thesymbolic.jetcommand.protocol.LinkState
import link.thesymbolic.jetcommand.protocol.Mode
import link.thesymbolic.jetcommand.protocol.formatTemp

@Composable
fun NowScreen(vm: BedJetViewModel, snackbar: SnackbarHostState, openDiagnostics: () -> Unit) {
    val stack by vm.stack.collectAsState()
    val link by vm.linkState.collectAsState()
    val update by vm.status.collectAsState()
    val remembered by vm.remembered.collectAsState()
    val scope = rememberCoroutineScope()
    val st = update?.status?.takeIf { link == LinkState.Ready }

    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            (stack?.label?.let { "$it · " } ?: "") + linkText(link),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (st == null) {
            Spacer(Modifier.weight(1f))
            if (stack == null) {
                remembered?.takeIf { vm.hasConnectPermission() }?.let { d ->
                    Button(vm::connectRemembered, Modifier.fillMaxWidth().height(56.dp)) { Text("Connect ${d.label}") }
                    Spacer(Modifier.height(12.dp))
                }
                OutlinedButton(openDiagnostics, Modifier.fillMaxWidth().height(56.dp)) { Text("Diagnostics") }
            }
            Spacer(Modifier.weight(1f))
            return@Column
        }

        Spacer(Modifier.height(24.dp))
        Text(st.mode.label, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        Text(formatTemp(st.actualTemp), fontSize = 72.sp, lineHeight = 76.sp)
        Text("→ ${formatTemp(st.targetTemp)}", fontSize = 36.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val r = st.remainingSeconds
        Text("%d:%02d left · fan %d%%".format(r / 3600, r / 60 % 60, st.fanPercent), fontSize = 20.sp)

        Spacer(Modifier.weight(1f))
        Stepper("Temp", { vm.press("temp down", Btn.TEMP_DOWN_C) }, { vm.press("temp up", Btn.TEMP_UP_C) })
        Spacer(Modifier.height(12.dp))
        Stepper("Fan", { vm.press("fan down", Btn.FAN_DOWN) }, { vm.press("fan up", Btn.FAN_UP) })

        // Stop sits apart from the steppers so a sleepy thumb aiming for − doesn't land on it.
        Spacer(Modifier.height(48.dp))
        if (st.mode != Mode.STANDBY) {
            OutlinedButton(
                onClick = {
                    val previous = st.mode
                    vm.setMode(Mode.STANDBY)
                    scope.launch {
                        // The snackbar's Short is ~4 s and Long ~10 s; time it out ourselves for ~5 s.
                        val result = withTimeoutOrNull(5_000) {
                            snackbar.showSnackbar("Stopped", actionLabel = "Undo", duration = SnackbarDuration.Indefinite)
                        }
                        if (result == null) snackbar.currentSnackbarData?.dismiss()
                        // WAIT can't be set by a button; programs aren't restored either.
                        if (result == SnackbarResult.ActionPerformed && Btn.forMode(previous) != null) vm.setMode(previous)
                    }
                },
                modifier = Modifier.fillMaxWidth(0.6f).height(52.dp),
            ) { Text("Stop") }
        } else Spacer(Modifier.height(52.dp))
    }
}

@Composable
private fun Stepper(name: String, down: () -> Unit, up: () -> Unit) =
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Big("$name −", down)
        Big("$name +", up)
    }

@Composable
private fun RowScope.Big(label: String, onClick: () -> Unit) =
    FilledTonalButton(onClick, Modifier.weight(1f).height(72.dp)) { Text(label, fontSize = 22.sp) }
