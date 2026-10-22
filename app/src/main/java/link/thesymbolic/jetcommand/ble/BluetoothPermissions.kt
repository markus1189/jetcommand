package link.thesymbolic.jetcommand.ble

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

val bluetoothPermissions = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

// Asked alongside, never required: without it the service still runs, its notification just stays hidden.
private val requested = if (Build.VERSION.SDK_INT >= 33) bluetoothPermissions + Manifest.permission.POST_NOTIFICATIONS else bluetoothPermissions

/** Shows [content] once scan and connect are granted; asks on first composition, then offers a retry button. */
@Composable
fun RequireBluetoothPermissions(content: @Composable () -> Unit) {
    val context = LocalContext.current
    fun granted() = bluetoothPermissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    var ok by remember { mutableStateOf(granted()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { ok = granted() }
    LaunchedEffect(Unit) { if (!ok) launcher.launch(requested) }
    if (ok) content()
    else Column(Modifier.padding(16.dp)) {
        Text("Bluetooth scan and connect permissions are needed to find and control a BedJet.")
        Button(onClick = { launcher.launch(requested) }) { Text("Grant") }
    }
}
