package link.thesymbolic.jetcommand.ble

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import link.thesymbolic.jetcommand.protocol.Uuids

data class ScannedDevice(val device: BluetoothDevice, val name: String?, val rssi: Int) {
    val address: String get() = device.address
}

/**
 * Not every BedJet advertises its service UUID (the web app and Bleak match on name prefix instead, see
 * BEDJET_BLUETOOTH_COMPARISON.md), and Android's ScanFilter matches names only exactly, so the scan runs
 * unfiltered and matches UUID or name prefix here. A unit renamed via the NAME characteristic may match
 * neither; [all] lists every device for that case.
 */
class BedJetScanner(context: Context) {
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter

    val isEnabled: Boolean get() = adapter?.isEnabled == true

    /** Found devices as advertisements arrive (repeats included). Fails if Bluetooth is off. */
    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun scan(all: Boolean = false): Flow<ScannedDevice> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner ?: throw IllegalStateException("Bluetooth is off")
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName
                if (all || isBedJet(name, result.scanRecord?.serviceUuids)) trySend(ScannedDevice(result.device, name, result.rssi))
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("Scan failed ($errorCode)"))
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, cb)
        awaitClose { runCatching { scanner.stopScan(cb) } }
    }

    companion object {
        private val SERVICE = ParcelUuid.fromString(Uuids.SERVICE)

        fun isBedJet(name: String?, serviceUuids: List<ParcelUuid>?) =
            serviceUuids?.contains(SERVICE) == true || name?.startsWith("bedjet", ignoreCase = true) == true
    }
}
