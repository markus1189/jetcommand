package link.thesymbolic.jetcommand

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import link.thesymbolic.jetcommand.night.FakeNightsSource
import link.thesymbolic.jetcommand.protocol.NightsSource

enum class Place(val label: String, val glyph: String) { NOW("Now", "◉"), PROGRAMS("Programs", "☰"), NIGHTS("Nights", "☾"), SETTINGS("Settings", "⚙") }

class MainActivity : ComponentActivity() {
    private val vm: BedJetViewModel by viewModels()
    // Seam for the Room-backed source; the fake stands in until it lands.
    private val nights: NightsSource by lazy { FakeNightsSource() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm.autoConnectOnce()
        setContent {
            JetCommandTheme {
                var place by rememberSaveable { mutableStateOf(Place.NOW) }
                val snackbar = remember { SnackbarHostState() }
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbar) },
                    bottomBar = {
                        NavigationBar {
                            Place.entries.forEach { p ->
                                NavigationBarItem(
                                    selected = p == place,
                                    onClick = { place = p },
                                    icon = { Text(p.glyph) },
                                    label = { Text(p.label) },
                                )
                            }
                        }
                    },
                ) { padding ->
                    Box(Modifier.padding(padding).fillMaxSize()) {
                        when (place) {
                            Place.NOW -> NowScreen(vm, snackbar, openDiagnostics = { place = Place.SETTINGS })
                            Place.PROGRAMS -> Coming(place.label)
                            Place.NIGHTS -> NightsScreen(nights)
                            Place.SETTINGS -> Column {
                                Text("Diagnostics", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(12.dp))
                                DebugScreen(vm)
                            }
                        }
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun Coming(what: String) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Text("$what: coming", color = MaterialTheme.colorScheme.onSurfaceVariant)
}
