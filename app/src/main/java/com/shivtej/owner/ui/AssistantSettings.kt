@file:OptIn(ExperimentalMaterial3Api::class)

package com.shivtej.owner.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shivtej.owner.App
import com.shivtej.owner.data.*
import com.shivtej.owner.util.AiClient
import com.shivtej.owner.util.Assistant
import com.shivtej.owner.util.AssistantData
import com.shivtej.owner.util.BackupRestore
import com.shivtej.owner.util.BillMath
import com.shivtej.owner.util.Images
import com.shivtej.owner.util.PaymentAmt
import com.shivtej.owner.util.PurchaseAmt
import com.shivtej.owner.util.SecretStore
import com.shivtej.owner.util.SupplierMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ================================================================== ASSISTANT
private data class Msg(val fromUser: Boolean, val text: String, val tag: String = "")

/**
 * The assistant. Offline answers always work (from this phone's data). When the owner turned on online AI in Settings,
 * the phone has internet and an API key is saved, the question goes to the AI service with a short summary;
 * if that fails, the offline answer is shown with the reason.
 */
@Composable
fun AssistantScreen(app: App) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val products by app.repo.products.collectAsStateWithLifecycle(emptyList())
    val bills by app.repo.bills.collectAsStateWithLifecycle(emptyList())
    val orders by app.repo.orders.collectAsStateWithLifecycle(emptyList())
    val pays by app.repo.customerPayments.collectAsStateWithLifecycle(emptyList())
    val returnRows by app.repo.returns.collectAsStateWithLifecycle(emptyList())
    val posC by app.repo.posCustomers.collectAsStateWithLifecycle(emptyList())
    val suppliers by app.repo.suppliers.collectAsStateWithLifecycle(emptyList())
    val purchases by app.repo.purchases.collectAsStateWithLifecycle(emptyList())
    val supPays by app.repo.supplierPayments.collectAsStateWithLifecycle(emptyList())
    val tasks by app.repo.tasks.collectAsStateWithLifecycle(emptyList())
    val online by app.net.online.collectAsStateWithLifecycle()

    val data = remember(products, bills, orders, pays, returnRows, posC, suppliers, purchases, supPays, tasks) {
        val pa = purchases.filter { it.status != "REJECTED" }.map { PurchaseAmt(it.id, it.supplierId, it.amount) }
        val pb = supPays.filter { it.status != "REJECTED" }.map { PaymentAmt(it.supplierId, it.billId, it.amount) }
        AssistantData(
            products = products,
            bills = bills,
            orders = orders,
            payments = pays,
            customerNames = posC.associate { BillMath.normMobile(it.mobile) to it.name }.filterKeys { it.length == 10 },
            openings = openingsOf(posC),
            supplierOwed = suppliers.map { s -> s.name to SupplierMath.totals(s.id, pa, pb).outstanding }.filter { it.second > 0.0 },
            openTasks = tasks,
            lowThreshold = app.prefs.lowThreshold,
            money = { v -> money(app, v) },
            now = System.currentTimeMillis(),
            webOrders = ledgerOrders(orders),
            ledgerReturns = ledgerReturns(returnRows)
        )
    }

    val msgs = remember {
        mutableStateListOf(Msg(false, "Hi! Ask me about today's sales, low or out of stock, what to reorder, customer dues (udhaar), supplier dues, pending orders, tasks, or the stock of an article such as 6100 size 8. I answer from this phone's data, also without internet."))
    }
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    fun ask(q: String) {
        if (q.isBlank()) return
        input = ""
        msgs.add(Msg(true, q))
        val offline = Assistant.offline(q, data)
        val key = SecretStore.aiKey(ctx)
        if (!app.prefs.aiOnline || key.isBlank()) { msgs.add(Msg(false, offline, "Offline")); return }
        if (!online) { msgs.add(Msg(false, offline, "Offline - no internet right now")); return }
        val slot = msgs.size
        msgs.add(Msg(false, "Asking the online AI...", "Online AI"))
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                AiClient.ask(key, app.prefs.aiModel, q, Assistant.summaryForAi(data))
            }
            msgs[slot] = res.fold(
                onSuccess = { Msg(false, it, "Online AI") },
                onFailure = { Msg(false, "Online AI could not answer: ${it.message}\n\nOffline answer:\n$offline", "Offline (online AI failed)") }
            )
        }
    }
    LaunchedEffect(msgs.size) { listState.animateScrollToItem(msgs.lastIndex) }

    val aiReady = app.prefs.aiOnline && SecretStore.hasAiKey(ctx)
    Column(Modifier.fillMaxSize()) {
        Text(
            if (aiReady) "Online AI is on: answers use the internet when available. Offline answers are used otherwise."
            else "Offline mode: answers come only from this phone's data.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), state = listState) {
            items(msgs.size) { i ->
                val m = msgs[i]
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start) {
                    Column(horizontalAlignment = if (m.fromUser) Alignment.End else Alignment.Start) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (m.fromUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.widthIn(max = 320.dp)
                        ) {
                            Text(m.text, modifier = Modifier.padding(12.dp), fontSize = 14.sp,
                                color = if (m.fromUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!m.fromUser && m.tag.isNotBlank()) Text(m.tag, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Low stock", "Out of stock", "Today's sales", "Best sellers", "Pending orders", "What to reorder",
                "Customer dues", "Supplier dues", "Compare last 7 days", "Open tasks", "Help")
                .forEach { AssistChip(onClick = { ask(it) }, label = { Text(it) }) }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, singleLine = true, placeholder = { Text("Ask something...") }, modifier = Modifier.weight(1f))
            IconButton(onClick = { ask(input) }) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
        }
    }
}

// ================================================================== SETTINGS
@Composable
fun SettingsScreen(app: App, onOpen: (Route) -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = app.prefs
    val online by app.net.online.collectAsStateWithLifecycle()
    val pending by app.repo.pendingOps.collectAsStateWithLifecycle(0)
    val failed by app.repo.failedOps.collectAsStateWithLifecycle(0)
    val failedList by app.repo.failedOpList.collectAsStateWithLifecycle(emptyList())
    val st by app.sync.state.collectAsStateWithLifecycle()

    var name by remember { mutableStateOf(prefs.storeName) }
    var address by remember { mutableStateOf(prefs.storeAddress) }
    var phone by remember { mutableStateOf(prefs.storePhone) }
    var currency by remember { mutableStateOf(prefs.currency) }
    var threshold by remember { mutableStateOf(prefs.lowThreshold.toString()) }
    var prefix by remember { mutableStateOf(prefs.barcodePrefix) }
    var printer by remember { mutableStateOf(prefs.printerNote) }
    var newCode by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf<android.net.Uri?>(null) }
    val fmt = remember { SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale.getDefault()) }

    // AI assistant settings. The key is never shown again once saved.
    var aiOn by remember { mutableStateOf(prefs.aiOnline) }
    var aiKeyText by remember { mutableStateOf("") }
    var aiKeySaved by remember { mutableStateOf(SecretStore.hasAiKey(ctx)) }
    var aiModel by remember { mutableStateOf(prefs.aiModel) }

    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) scope.launch {
            try {
                withContext(Dispatchers.IO) { BackupRestore.exportTo(ctx, app.db, uri) }
                toast(ctx, "Backup saved")
            } catch (e: Exception) { toast(ctx, "Backup failed: ${e.message}") }
        }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) confirmRestore = uri }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        SectionTitle("Sync status")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(if (online) "ONLINE" else "OFFLINE", fontWeight = FontWeight.Bold, color = if (online) GreenOk else AmberLow)
                Text("Last sync: " + if (st.lastSync == 0L) "never" else fmt.format(Date(st.lastSync)))
                Text("Waiting to upload: $pending change(s)")
                Text("Failed: $failed change(s)", color = if (failed > 0) RedOut else MaterialTheme.colorScheme.onSurface)
                st.lastError?.let { Text(it, color = RedOut, fontSize = 13.sp) }
                st.notice?.let { Text(it, color = AmberLow, fontSize = 13.sp) }
                failedList.forEach { Text("• ${it.type}: ${it.error ?: "error"}", fontSize = 12.sp, color = RedOut) }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !st.running, onClick = {
                        scope.launch {
                            val ok = app.sync.syncNow()
                            toast(ctx, if (ok) "Sync complete" else "Sync did not finish - see message")
                        }
                    }) { Text(if (st.running) "Syncing..." else "Sync now") }
                    if (failed > 0) OutlinedButton(onClick = { scope.launch { app.repo.retryFailed() } }) { Text("Retry failed") }
                }
            }
        }

        SectionTitle("Appearance, tasks and assistant")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(8.dp)) {
                TextButton(onClick = { onOpen(Route.ThemeSettings) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Theme, colours and wallpaper - light, dark, red & white", modifier = Modifier.fillMaxWidth())
                }
                TextButton(onClick = { onOpen(Route.Tasks) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Tasks and reminders - due dates and notifications", modifier = Modifier.fillMaxWidth())
                }
            }
        }

        SectionTitle("Bill and printer (update 2)")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(8.dp)) {
                TextButton(onClick = { onOpen(Route.ShopSettings) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Shop & bill settings - logo, website, Instagram, GST, notes", modifier = Modifier.fillMaxWidth())
                }
                TextButton(onClick = { onOpen(Route.BillDesign) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Bill theme and design - colours, background, picture", modifier = Modifier.fillMaxWidth())
                }
                TextButton(onClick = { onOpen(Route.Printer) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Bluetooth printer - ATPOS AT-301 (80 mm)", modifier = Modifier.fillMaxWidth())
                }
            }
        }

        SectionTitle("Store information")
        OutlinedTextField(name, { name = it }, label = { Text("Store name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(address, { address = it }, label = { Text("Address (on receipt)") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        OutlinedTextField(phone, { phone = it }, label = { Text("Phone (on receipt)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(currency, { currency = it.take(4) }, label = { Text("Currency") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(threshold, { threshold = it.filter(Char::isDigit).take(3) }, label = { Text("Low stock at") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
        }
        OutlinedTextField(prefix, { prefix = it.filter(Char::isLetterOrDigit).take(6).uppercase() }, label = { Text("Barcode prefix (new barcodes)") },
            singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        OutlinedTextField(printer, { printer = it }, label = { Text("Printer note (receipts print through Android printing)") },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        Button(onClick = {
            prefs.storeName = name.trim().ifBlank { "Shivtej Footwear" }
            prefs.storeAddress = address.trim(); prefs.storePhone = phone.trim()
            prefs.currency = currency.ifBlank { "₹" }
            prefs.lowThreshold = threshold.toIntOrNull() ?: 2
            prefs.barcodePrefix = prefix.ifBlank { "SW" }
            prefs.printerNote = printer.trim()
            toast(ctx, "Settings saved")
        }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(50.dp)) { Text("Save settings") }

        SectionTitle("AI assistant")
        Text("The offline assistant always works and answers from this phone's data. Online AI gives longer, chat-style answers when the phone has internet.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Use online AI when internet is available", modifier = Modifier.weight(1f))
            Switch(checked = aiOn, onCheckedChange = { aiOn = it; prefs.aiOnline = it })
        }
        Text(
            if (aiKeySaved) "API key: saved on this phone (encrypted, not shown)" else "API key: not saved",
            fontSize = 12.sp, color = if (aiKeySaved) GreenOk else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
        OutlinedTextField(aiKeyText, { aiKeyText = it.trim() }, label = { Text("Paste API key") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            Button(enabled = aiKeyText.length >= 20, onClick = {
                if (SecretStore.setAiKey(ctx, aiKeyText)) {
                    aiKeyText = ""; aiKeySaved = SecretStore.hasAiKey(ctx); toast(ctx, "API key saved on this phone")
                } else toast(ctx, "Could not save the key on this phone")
            }) { Text("Save key") }
            if (aiKeySaved) OutlinedButton(onClick = {
                SecretStore.setAiKey(ctx, ""); aiKeySaved = false; aiOn = false; prefs.aiOnline = false
                toast(ctx, "API key removed")
            }) { Text("Remove key") }
        }
        OutlinedTextField(aiModel, { aiModel = it.trim().take(60) }, label = { Text("Model name") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        OutlinedButton(onClick = {
            prefs.aiModel = aiModel.ifBlank { "claude-haiku-5-5" }
            aiModel = prefs.aiModel
            toast(ctx, "Model saved")
        }, modifier = Modifier.padding(top = 4.dp)) { Text("Save model") }
        Text("Privacy: when online AI is on and you ask a question, a short summary of totals (sales, stock, dues, tasks) is sent to the AI service. Customer phone numbers are not sent. Turn it off to keep everything on this phone.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))

        SectionTitle("Owner security")
        OutlinedTextField(newCode, { newCode = it }, label = { Text("Change owner code (must match the server)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        OutlinedButton(enabled = newCode.length >= 4, onClick = {
            scope.launch {
                val r = app.sync.verifyCode(newCode.trim())
                if (r.isSuccess) { prefs.ownerCode = newCode.trim(); newCode = ""; app.sync.kick(); toast(ctx, "Owner code updated") }
                else toast(ctx, "Server did not accept that code")
            }
        }, modifier = Modifier.padding(top = 4.dp)) { Text("Verify and save code") }
        OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(8) }, label = { Text("App lock PIN (4-8 digits)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            OutlinedButton(enabled = pin.length in 4..8, onClick = { prefs.appPin = pin; pin = ""; toast(ctx, "PIN set - asked on next start") }) { Text("Set PIN") }
            if (prefs.appPin.isNotBlank()) OutlinedButton(onClick = { prefs.appPin = ""; toast(ctx, "PIN removed") }) { Text("Remove PIN") }
        }

        SectionTitle("Backup and restore")
        Text("Backup copies all data stored on this phone (products, bills, stock, queue) to a file you choose.", fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            Button(onClick = { backup.launch("Shivtej-backup-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".db") }) { Text("Backup") }
            OutlinedButton(onClick = { restore.launch(arrayOf("*/*")) }) { Text("Restore") }
        }

        SectionTitle("Local data")
        OutlinedButton(onClick = { confirmClear = true }) { Text("Clear temporary cache") }
        Text("Deletes only temporary camera photos. Products, photos, stock, bills, returns, purchases, customers and unsynced changes are NOT touched.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(32.dp))
    }

    if (confirmClear) ConfirmDialog(
        "Clear temporary cache?",
        "Only temporary camera photos are deleted. Nothing else on this phone is changed.",
        "Clear",
        {
            confirmClear = false
            scope.launch {
                val n = withContext(Dispatchers.IO) { Images.clearTempCache(ctx) }
                toast(ctx, "Temporary cache cleared ($n file(s))")
            }
        },
        { confirmClear = false }
    )
    confirmRestore?.let { uri ->
        ConfirmDialog("Restore backup?",
            "The backup file is checked now; if it is not usable nothing changes. Then the app closes. When you open it again, the current data is kept as a copy in the app folder and the backup replaces the data on this phone.",
            "Restore",
            {
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { BackupRestore.stage(ctx, uri) }
                        toast(ctx, "Backup checked. The app will close; open it again to apply the restore.")
                        android.os.Process.killProcess(android.os.Process.myPid())
                    } catch (e: Exception) { toast(ctx, "Restore stopped: ${e.message}") }
                }
            }, { confirmRestore = null })
    }
}
