@file:OptIn(ExperimentalMaterial3Api::class)

package com.shivtej.owner.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shivtej.owner.App
import com.shivtej.owner.util.Invoice
import com.shivtej.owner.util.PrinterManager
import com.shivtej.owner.util.PrinterStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Returns a function that runs an action once Bluetooth may be used for PRINTING to the saved printer
 * (on Android 12+ that is the "Nearby devices" permission; older Android needs nothing).
 */
@Composable
fun rememberWithBluetooth(app: App, scan: Boolean = false): (() -> Unit) -> Unit {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val ok = if (scan) app.printer.hasPermission() else app.printer.canConnect()
        if (ok) pending?.invoke() else toast(ctx, "Bluetooth permission is needed to use the printer")
        pending = null
        app.printer.refreshStatus()
    }
    return { action ->
        val ok = if (scan) app.printer.hasPermission() else app.printer.canConnect()
        if (ok) action()
        else {
            pending = action
            launcher.launch(PrinterManager.neededPermissions())
        }
    }
}

@Composable
fun PrinterScreen(app: App) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pm = app.printer
    val st by pm.state.collectAsStateWithLifecycle()
    val devices by pm.devices.collectAsStateWithLifecycle()
    val scanning by pm.scanning.collectAsStateWithLifecycle()
    val withBt = rememberWithBluetooth(app, scan = true)
    var busy by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(app.prefs.printMode) }
    var note by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        pm.refreshStatus()
        pm.refreshBonded()
    }
    DisposableEffect(Unit) { onDispose { pm.stopScan() } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SoftGrey)) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val dot = when (st.status) {
                            PrinterStatus.CONNECTED -> GreenOk
                            PrinterStatus.CONNECTING -> AmberLow
                            PrinterStatus.DISCONNECTED -> AmberLow
                            else -> RedOut
                        }
                        Box(Modifier.size(12.dp).background(dot, CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when (st.status) {
                                PrinterStatus.CONNECTED -> "Printer connected"
                                PrinterStatus.CONNECTING -> "Connecting..."
                                PrinterStatus.DISCONNECTED -> "Printer not connected"
                                PrinterStatus.NOT_SAVED -> "No printer saved"
                                PrinterStatus.BT_OFF -> "Bluetooth is off"
                                PrinterStatus.NO_PERMISSION -> "Bluetooth permission needed"
                                PrinterStatus.NO_BLUETOOTH -> "No Bluetooth on this phone"
                            }, fontWeight = FontWeight.Bold, fontSize = 18.sp
                        )
                    }
                    Text(st.message, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    if (pm.savedAddress.isNotBlank()) {
                        Text("Saved printer: ${pm.savedName.ifBlank { "Printer" }} (${pm.savedAddress})", fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                    note?.let { Text(it, fontSize = 13.sp, color = if (it.startsWith("Test print sent")) GreenOk else RedOut, modifier = Modifier.padding(top = 6.dp)) }
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = pm.savedAddress.isNotBlank() && !busy && st.status != PrinterStatus.CONNECTING,
                            onClick = {
                                withBt {
                                    busy = true; note = null
                                    scope.launch {
                                        val r = pm.connect(pm.savedAddress, pm.savedName, true)
                                        busy = false
                                        r.exceptionOrNull()?.let { note = it.message }
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text(if (st.status == PrinterStatus.CONNECTED) "Reconnect" else "Connect") }
                        OutlinedButton(
                            enabled = st.status == PrinterStatus.CONNECTED,
                            onClick = { pm.disconnect(); note = null },
                            modifier = Modifier.weight(1f)
                        ) { Text("Disconnect") }
                    }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = pm.savedAddress.isNotBlank() && !busy,
                            onClick = {
                                withBt {
                                    busy = true; note = null
                                    scope.launch {
                                        val logo = withContext(Dispatchers.Default) {
                                            Invoice.loadLogo(ctx, com.shivtej.owner.data.BillMeta.currentShop(app.prefs))
                                        }
                                        val r = pm.testPrint(logo)
                                        busy = false
                                        note = if (r.isSuccess) "Test print sent. Check that the paper came out clearly." else r.exceptionOrNull()?.message
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = BrandInk),
                            modifier = Modifier.weight(1f)
                        ) { Icon(Icons.Filled.Print, null); Spacer(Modifier.width(6.dp)); Text("Test print") }
                        OutlinedButton(
                            enabled = pm.savedAddress.isNotBlank() && !busy,
                            onClick = { pm.forget(); note = null },
                            modifier = Modifier.weight(1f)
                        ) { Text("Forget") }
                    }
                    if (st.status == PrinterStatus.BT_OFF) {
                        TextButton(onClick = {
                            try { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { toast(ctx, "Open Bluetooth settings") }
                        }) { Text("Open Bluetooth settings") }
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("How bills are printed", fontWeight = FontWeight.Bold)
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip("Bill picture (logo, QR, ₹)", mode == "graphic") { mode = "graphic"; app.prefs.printMode = "graphic" }
                        Chip("Plain text (Rs.)", mode == "text") { mode = "text"; app.prefs.printMode = "text" }
                    }
                    Text(
                        "Bill picture prints the shop logo, QR codes and the ₹ sign exactly like the bill on screen. " +
                            "If your printer prints garbage in picture mode, choose Plain text: it works on every ESC/POS printer.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp)
                    )
                    Text("Paper: 80 mm (576 dots) - ATPOS AT-301", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Find your printer", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Button(onClick = { withBt { pm.startScan() } }, enabled = !scanning) {
                    Icon(Icons.Filled.BluetoothSearching, null); Spacer(Modifier.width(6.dp)); Text(if (scanning) "Searching..." else "Scan")
                }
            }
            Text(
                "Switch the printer ON and keep it near the phone. If it is not listed, pair it once in the phone's Bluetooth settings " +
                    "(PIN is usually 0000 or 1234), come back and tap Scan.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
        }

        if (devices.isEmpty()) item { EmptyState("No Bluetooth devices yet. Tap Scan.") }
        items(devices, key = { it.address }) { d ->
            val saved = d.address == pm.savedAddress
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Print, null, tint = if (saved) BrandRed else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(d.address + if (d.bonded) " · paired" else " · not paired", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (saved) Text("Saved printer", fontSize = 12.sp, color = GreenOk, fontWeight = FontWeight.SemiBold)
                    }
                    if (!d.bonded) {
                        OutlinedButton(onClick = {
                            withBt { if (!pm.pair(d.address)) toast(ctx, "Could not start pairing") }
                        }) { Text("Pair") }
                    } else {
                        Button(enabled = !busy, onClick = {
                            withBt {
                                busy = true; note = null
                                scope.launch {
                                    pm.stopScan()
                                    val r = pm.connect(d.address, d.name, true)
                                    busy = false
                                    r.exceptionOrNull()?.let { note = it.message }
                                }
                            }
                        }) { Text("Connect") }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}
