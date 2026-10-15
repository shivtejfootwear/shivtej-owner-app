@file:OptIn(ExperimentalMaterial3Api::class)

package com.shivtej.owner.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shivtej.owner.App
import com.shivtej.owner.data.*
import com.shivtej.owner.util.Receipt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ================================================================== STOCK
private data class StockRow(val p: Product, val v: Variant, val s: SizeStock)

@Composable
fun StockScreen(app: App, initialTab: Int, initialFilter: String) {
    var tab by remember(initialTab) { mutableIntStateOf(initialTab) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Inventory") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Stock history") })
        }
        if (tab == 0) InventoryTab(app, initialFilter) else HistoryTab(app)
    }
}

@Composable
private fun InventoryTab(app: App, initialFilter: String) {
    val products by app.repo.products.collectAsStateWithLifecycle(emptyList())
    var query by remember { mutableStateOf("") }
    var filter by remember(initialFilter) { mutableStateOf(initialFilter) }
    var editing by remember { mutableStateOf<StockRow?>(null) }
    var sizeFilter by remember { mutableStateOf("all") }
    val low = app.prefs.lowThreshold
    val allSizes = remember(products) { products.flatMap { p -> p.variants.flatMap { v -> v.sizes.map { it.size } } }
        .distinct().sortedBy { it.toDoubleOrNull() ?: 9999.0 } }

    val rows = products.flatMap { p -> p.variants.flatMap { v -> v.sizes.map { StockRow(p, v, it) } } }.filter { r ->
        val q = query.trim().lowercase()
        val m = q.isEmpty() || listOf(r.p.name, r.p.article, r.p.brand, r.v.colour, r.s.size, (r.s.barcodes + r.s.barcode).joinToString(" ")).any { it.lowercase().contains(q) }
        val f = when (filter) { "low" -> r.s.qty in 1..low; "out" -> r.s.qty <= 0; else -> true }
        val sz = sizeFilter == "all" || r.s.size == sizeFilter
        m && f && sz
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        SearchField(query, { query = it }, "Search article, product, colour, size, barcode", Modifier.padding(top = 8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("all" to "All", "low" to "Low stock", "out" to "Out of stock").forEach { (k, l) -> Chip(l, filter == k) { filter = k } }
        }
        SizeFilterRow(allSizes, sizeFilter) { sizeFilter = it }
        Text("${rows.size} item(s) · ${rows.sumOf { it.s.qty }} pairs", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (rows.isEmpty()) EmptyState("Nothing to show.")
        LazyColumn {
            items(rows, key = { it.p.id + "|" + it.v.colour + "|" + it.s.size }) { r ->
                Row(Modifier.fillMaxWidth().clickable { editing = r }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${r.p.article} · ${r.p.name}", fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text("${r.v.colour} / Size ${r.s.size}", fontSize = 13.sp)
                        Text("Barcodes: ${r.s.barcodes.size} of ${r.s.qty} pieces", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${r.s.qty}", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        StockBadge(r.s.qty, low)
                    }
                }
                HorizontalDivider()
            }
        }
    }
    editing?.let { StockActionDialog(app, it) { editing = null } }
}

/** "All sizes" or one size (6, 7, 8 ...). Shared by the Products and Inventory lists. */
@Composable
fun SizeFilterRow(sizes: List<String>, selected: String, onPick: (String) -> Unit) {
    if (sizes.isEmpty()) return
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip("All sizes", selected == "all") { onPick("all") }
        sizes.forEach { s -> Chip("Size $s", selected == s) { onPick(s) } }
    }
}

@Composable
private fun StockActionDialog(app: App, r: StockRow, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf("add") }
    var amount by remember { mutableStateOf("") }
    val n = amount.toIntOrNull()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("${r.p.article} · ${r.v.colour} / ${r.s.size}") },
        text = {
            Column {
                Text("Current stock: ${r.s.qty}")
                Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("Add", mode == "add") { mode = "add" }
                    Chip("Remove", mode == "remove") { mode = "remove" }
                    Chip("Set exact", mode == "set") { mode = "set" }
                }
                OutlinedTextField(
                    amount, { amount = it.filter(Char::isDigit).take(6) }, singleLine = true,
                    label = { Text(if (mode == "set") "New stock" else "Quantity") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        },
        confirmButton = {
            TextButton(enabled = n != null && (mode == "set" || n > 0), onClick = {
                scope.launch {
                    try {
                        when (mode) {
                            "add" -> app.repo.adjustStock(r.p.id, r.v.colour, r.s.size, n!!, "ADD")
                            "remove" -> app.repo.adjustStock(r.p.id, r.v.colour, r.s.size, -n!!, "REMOVE")
                            else -> app.repo.setStock(r.p.id, r.v.colour, r.s.size, n!!)
                        }
                        onClose()
                    } catch (e: Exception) { toast(ctx, e.message ?: "Failed") }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } }
    )
}

@Composable
private fun HistoryTab(app: App) {
    val log by app.repo.stockLog.collectAsStateWithLifecycle(emptyList())
    val fmt = remember { SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()) }
    if (log.isEmpty()) { EmptyState("No stock changes yet."); return }
    LazyColumn(Modifier.padding(horizontal = 12.dp)) {
        items(log, key = { it.opId }) { l ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${l.article} · ${l.colour} / ${l.size}", fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text("${l.reason} · ${fmt.format(Date(l.createdAt))}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text((if (l.delta > 0) "+" else "") + l.delta, fontWeight = FontWeight.Bold, fontSize = 18.sp,
                        color = if (l.delta >= 0) GreenOk else RedOut)
                    Text(if (l.synced) "synced" else "waiting to sync", fontSize = 10.sp,
                        color = if (l.synced) GreenOk else AmberLow)
                }
            }
            HorizontalDivider()
        }
    }
}

// ================================================================== POS
// PosScreen and ReceiptScreen now live in Billing.kt (update 2). The two pickers below are shared by it.

@Composable
fun ProductSearchDialog(app: App, products: List<Product>, title: String, onDismiss: () -> Unit, onPick: (Product) -> Unit) {
    var q by remember { mutableStateOf("") }
    val list = products.filter {
        q.isBlank() || listOf(it.name, it.article, it.brand).any { s -> s.lowercase().contains(q.trim().lowercase()) }
    }.take(30)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                SearchField(q, { q = it }, "Search product / article")
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(list, key = { it.id }) { p ->
                        Row(Modifier.fillMaxWidth().clickable { onPick(p) }.padding(vertical = 10.dp)) {
                            Column {
                                Text(p.name, fontWeight = FontWeight.SemiBold)
                                Text("${p.article} · ${money(app, p.price)}", fontSize = 12.sp)
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun VariantPickerDialog(
    app: App, p: Product, confirmLabel: String, onDismiss: () -> Unit,
    onConfirm: (colour: String, size: SizeStock, qty: Int) -> Unit
) {
    var colour by remember(p.id) { mutableStateOf(p.variants.firstOrNull()?.colour ?: "") }
    var size by remember(p.id) { mutableStateOf<String?>(null) }
    var qty by remember(p.id) { mutableIntStateOf(1) }
    val variant = p.variants.firstOrNull { it.colour == colour }
    val sel = variant?.sizes?.firstOrNull { it.size == size }
    val assign = confirmLabel.startsWith("Assign")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${p.article} · ${p.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Colour", fontWeight = FontWeight.SemiBold)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    p.variants.forEach { v -> Chip(v.colour, colour == v.colour) { colour = v.colour; size = null } }
                }
                Text("Size (stock)", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    variant?.sizes?.forEach { s -> Chip("${s.size} (${s.qty})", size == s.size) { size = s.size } }
                }
                if (!assign) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Qty")
                        IconButton(onClick = { if (qty > 1) qty-- }) { Icon(Icons.Filled.Remove, "Less") }
                        Text("$qty", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        IconButton(onClick = { qty++ }) { Icon(Icons.Filled.Add, "More") }
                    }
                    Text("Price: ${money(app, p.price)}", fontWeight = FontWeight.Bold)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = sel != null && (assign || sel.qty > 0), onClick = { onConfirm(colour, sel!!, qty) }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
