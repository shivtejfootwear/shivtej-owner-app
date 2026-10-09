@file:OptIn(ExperimentalMaterial3Api::class)

package com.shivtej.owner.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shivtej.owner.App
import com.shivtej.owner.data.*
import com.shivtej.owner.util.BackupRestore
import com.shivtej.owner.util.Images
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ================================================================== OFFLINE ASSISTANT
private data class Msg(val fromUser: Boolean, val text: String)

/** Rule-based assistant. It answers from the data stored on this phone, so it works without internet. */
@Composable
fun AssistantScreen(app: App) {
    val products by app.repo.products.collectAsStateWithLifecycle(emptyList())
    val bills by app.repo.bills.collectAsStateWithLifecycle(emptyList())
    val orders by app.repo.orders.collectAsStateWithLifecycle(emptyList())
    val msgs = remember {
        mutableStateListOf(Msg(false, "Hi! I answer from the data saved on this phone, so I work offline too. Ask me about low stock, today's sales, best sellers, pending orders or the stock of any product."))
    }
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    fun ask(q: String) {
        if (q.isBlank()) return
        msgs.add(Msg(true, q))
        msgs.add(Msg(false, answer(app, q, products, bills, orders)))
        input = ""
    }
    LaunchedEffect(msgs.size) { listState.animateScrollToItem(msgs.lastIndex) }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), state = listState) {
            items(msgs.size) { i ->
                val m = msgs[i]
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (m.fromUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.widthIn(max = 320.dp)
                    ) {
                        Text(m.text, modifier = Modifier.padding(12.dp), fontSize = 14.sp,
                            color = if (m.fromUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Low stock", "Out of stock", "Today's sales", "Best sellers", "Pending orders", "What to reorder", "Help")
                .forEach { AssistChip(onClick = { ask(it) }, label = { Text(it) }) }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, singleLine = true, placeholder = { Text("Ask something...") }, modifier = Modifier.weight(1f))
            IconButton(onClick = { ask(input) }) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
        }
    }
}

private fun answer(app: App, q: String, products: List<Product>, bills: List<BillE>, orders: List<OrderE>): String {
    val t = q.lowercase()
    val low = app.prefs.lowThreshold
    val rows = products.flatMap { p -> p.variants.flatMap { v -> v.sizes.map { Triple(p, v, it) } } }
    fun line(r: Triple<Product, Variant, SizeStock>) = "• ${r.first.article} ${r.first.name} - ${r.second.colour} / ${r.third.size}: ${r.third.qty}"
    val month = startOfToday() - 29L * 86_400_000
    val soldQty = bills.filter { it.createdAt >= month }.flatMap { parseBillItems(it.itemsJson) }

    return when {
        "out of stock" in t || t.trim() == "out" -> {
            val l = rows.filter { it.third.qty <= 0 }
            if (l.isEmpty()) "Nothing is out of stock." else "${l.size} size(s) are out of stock:\n" + l.take(25).joinToString("\n") { line(it) } + if (l.size > 25) "\n...and ${l.size - 25} more." else ""
        }
        "low" in t -> {
            val l = rows.filter { it.third.qty in 1..low }.sortedBy { it.third.qty }
            if (l.isEmpty()) "No sizes are low (threshold $low)." else "${l.size} size(s) are low (≤ $low):\n" + l.take(25).joinToString("\n") { line(it) } + if (l.size > 25) "\n...and ${l.size - 25} more." else ""
        }
        "reorder" in t || "restock" in t || "buy" in t -> {
            val sellMap = soldQty.groupBy { Triple(it.productId, it.colour, it.size) }.mapValues { e -> e.value.sumOf { it.qty } }
            val l = rows.filter { it.third.qty <= low }.sortedByDescending { sellMap[Triple(it.first.id, it.second.colour, it.third.size)] ?: 0 }
            if (l.isEmpty()) "Nothing needs reordering right now." else
                "Reorder first (fast sellers in the last 30 days):\n" + l.take(15).joinToString("\n") {
                    line(it) + "  (sold ${sellMap[Triple(it.first.id, it.second.colour, it.third.size)] ?: 0})"
                }
        }
        "today" in t || "sale" in t || "bill" in t -> {
            val b = bills.filter { it.createdAt >= startOfToday() }
            val o = orders.filter { it.createdAt >= startOfToday() && it.orderStatus != "Cancelled" }
            "Today: ${b.size} POS bill(s) = ${money(app, b.sumOf { it.total })}, ${o.size} online order(s) = ${money(app, o.sumOf { it.total })}."
        }
        "best" in t || "top" in t || "selling" in t -> {
            val top = soldQty.groupBy { it.article + " " + it.name }.map { it.key to it.value.sumOf { x -> x.qty } }.sortedByDescending { it.second }.take(8)
            if (top.isEmpty()) "No POS sales in the last 30 days yet." else "Best sellers (30 days):\n" + top.joinToString("\n") { "• ${it.first}: ${it.second} pcs" }
        }
        "pending" in t || "order" in t -> {
            val n = orders.filter { it.orderStatus == "New" }
            val s = orders.count { it.orderStatus in listOf("Confirmed", "Packed") }
            if (orders.isEmpty()) "There are no website orders yet." else
                "${n.size} new order(s) waiting for confirmation, $s confirmed/packed and ready to ship." +
                    if (n.isNotEmpty()) "\n" + n.take(8).joinToString("\n") { "• #${it.id} - ${money(app, it.total)}" } else ""
        }
        "help" in t -> "Try: low stock, out of stock, today's sales, best sellers, pending orders, what to reorder, or type a product / article / colour (for example \"6100\" or \"black\") to see its stock."
        else -> {
            val words = t.split(" ", ",").filter { it.length >= 2 }
            val found = products.filter { p ->
                words.any { w -> p.name.lowercase().contains(w) || p.article.lowercase().contains(w) || p.brand.lowercase().contains(w) ||
                    p.variants.any { it.colour.lowercase().contains(w) } }
            }.take(5)
            if (found.isEmpty()) "I could not find that. Type \"help\" to see what I can answer."
            else found.joinToString("\n\n") { p ->
                "${p.article} ${p.name} (${money(app, p.price)}) - total ${p.totalStock}\n" +
                    p.variants.joinToString("\n") { v -> "  ${v.colour}: " + v.sizes.joinToString(", ") { "${it.size}=${it.qty}" } }
            }
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
