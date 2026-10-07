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
    val low = app.prefs.lowThreshold

    val rows = products.flatMap { p -> p.variants.flatMap { v -> v.sizes.map { StockRow(p, v, it) } } }.filter { r ->
        val q = query.trim().lowercase()
        val m = q.isEmpty() || listOf(r.p.name, r.p.article, r.p.brand, r.v.colour, r.s.size, r.s.barcode).any { it.lowercase().contains(q) }
        val f = when (filter) { "low" -> r.s.qty in 1..low; "out" -> r.s.qty <= 0; else -> true }
        m && f
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        SearchField(query, { query = it }, "Search article, product, colour, size, barcode", Modifier.padding(top = 8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("all" to "All", "low" to "Low stock", "out" to "Out of stock").forEach { (k, l) -> Chip(l, filter == k) { filter = k } }
        }
        Text("${rows.size} item(s) · ${rows.sumOf { it.s.qty }} pairs", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (rows.isEmpty()) EmptyState("Nothing to show.")
        LazyColumn {
            items(rows, key = { it.p.id + "|" + it.v.colour + "|" + it.s.size }) { r ->
                Row(Modifier.fillMaxWidth().clickable { editing = r }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${r.p.article} · ${r.p.name}", fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text("${r.v.colour} / Size ${r.s.size}", fontSize = 13.sp)
                        Text("Barcode: ${r.s.barcode.ifBlank { "-" }}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
@Composable
fun PosScreen(app: App, nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val products by app.repo.products.collectAsStateWithLifecycle(emptyList())
    val cart = app.cart
    val withCamera = rememberWithCamera()

    var query by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var hit by remember { mutableStateOf<Hit?>(null) }
    var unknownCode by remember { mutableStateOf<String?>(null) }
    var assignCode by remember { mutableStateOf<String?>(null) }
    var picker by remember { mutableStateOf<Product?>(null) }
    var method by remember { mutableStateOf("Cash") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    fun handleCode(code: String) {
        scope.launch {
            val h = app.repo.lookup(code)
            if (h != null) hit = h else unknownCode = code.trim()
        }
    }

    val results = if (query.isBlank()) emptyList() else {
        val q = query.trim().lowercase()
        products.filter { p ->
            p.active && (listOf(p.name, p.article, p.brand, p.category).any { it.lowercase().contains(q) } ||
                p.variants.any { v -> v.colour.lowercase().contains(q) || v.sizes.any { it.barcode.lowercase() == q } })
        }.take(20)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("Search product / type barcode") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) handleCode(query) }),
                trailingIcon = {
                    IconButton(onClick = { if (query.isNotBlank()) handleCode(query) }) { Icon(Icons.Filled.Search, "Find barcode") }
                },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick = { withCamera { scanning = true } }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.QrCodeScanner, "Scan")
            }
        }

        if (results.isNotEmpty()) {
            Card(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                    results.forEach { p ->
                        Row(Modifier.fillMaxWidth().clickable { picker = p }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Thumb(p.allImages.firstOrNull(), 44.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text("${p.article} · ${money(app, p.price)}", fontSize = 12.sp)
                            }
                            StockBadge(p.totalStock, app.prefs.lowThreshold)
                        }
                        HorizontalDivider()
                    }
                }
            }
        }

        SectionTitle("Current bill")
        if (cart.lines.isEmpty()) EmptyState("Scan a barcode or search a product to start a bill.")
        LazyColumn(Modifier.weight(1f)) {
            items(cart.lines.size) { i ->
                val l = cart.lines[i]
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(l.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text("${l.article} · ${l.colour} / Size ${l.size}", fontSize = 12.sp)
                            Text("Barcode ${l.barcode.ifBlank { "-" }} · ${money(app, l.price)} each", fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { cart.change(i, -1) }) { Icon(Icons.Filled.Remove, "Less") }
                                Text("${l.qty}", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                IconButton(onClick = { cart.change(i, 1)?.let { toast(ctx, it) } }) { Icon(Icons.Filled.Add, "More") }
                                IconButton(onClick = { cart.remove(i) }) { Icon(Icons.Filled.Delete, "Remove") }
                            }
                            Text(money(app, l.total), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Grand total", fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text(money(app, cart.total), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Cash", "UPI", "Card", "Other").forEach { Chip(it, method == it) { method = it } }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { confirmClear = true }, enabled = cart.lines.isNotEmpty() && !busy,
                modifier = Modifier.weight(1f).height(54.dp)
            ) { Text("Clear") }
            Button(
                enabled = cart.lines.isNotEmpty() && !busy,
                modifier = Modifier.weight(2f).height(54.dp),
                onClick = {
                    busy = true; error = null
                    scope.launch {
                        try {
                            // online: refresh from the server first so we bill against the latest stock
                            if (app.net.online.value) withTimeoutOrNull(6000) { app.sync.syncNow() }
                            val fresh = app.repo.products.first()
                            val problems = cart.lines.mapNotNull { l ->
                                val q = fresh.firstOrNull { it.id == l.productId }?.variants?.firstOrNull { it.colour == l.colour }
                                    ?.sizes?.firstOrNull { it.size == l.size }?.qty ?: 0
                                if (l.qty > q) "${l.name} ${l.colour}/${l.size}: only $q left" else null
                            }
                            if (problems.isNotEmpty()) {
                                error = problems.joinToString("\n")
                            } else {
                                val bill = app.repo.createBill(cart.lines.toList(), method)
                                cart.clear()
                                nav.replace(Route.Receipt(bill.id))
                            }
                        } catch (e: Exception) {
                            error = e.message ?: "Could not complete the bill"
                        } finally { busy = false }
                    }
                }
            ) { Text(if (busy) "Please wait..." else "Complete bill") }
        }
    }

    // ---- scanner
    if (scanning) ScannerDialog(onResult = { scanning = false; handleCode(it) }, onDismiss = { scanning = false })

    // ---- scanned item preview
    hit?.let { h ->
        var qty by remember(h) { mutableIntStateOf(1) }
        AlertDialog(
            onDismissRequest = { hit = null },
            title = { Text("Item found") },
            text = {
                Column {
                    Text("Article: ${h.product.article}")
                    Text("Product: ${h.product.name}")
                    Text("Colour: ${h.colour}")
                    Text("Size: ${h.size.size}")
                    Text("Price: ${money(app, h.product.price)}", fontWeight = FontWeight.Bold)
                    Text("Stock: ${h.size.qty}", color = if (h.size.qty > 0) GreenOk else RedOut)
                    Text("Barcode: ${h.size.barcode}")
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        IconButton(onClick = { if (qty > 1) qty-- }) { Icon(Icons.Filled.Remove, "Less") }
                        Text("$qty", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        IconButton(onClick = { qty++ }) { Icon(Icons.Filled.Add, "More") }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = h.size.qty > 0, onClick = {
                    val err = cart.add(CartLine(h.product.id, h.product.article, h.product.name, h.colour, h.size.size,
                        h.size.barcode, qty, h.product.price, h.size.qty))
                    if (err != null) toast(ctx, err) else { hit = null; query = "" }
                }) { Text("Add to bill") }
            },
            dismissButton = { TextButton(onClick = { hit = null }) { Text("Cancel") } }
        )
    }

    // ---- unknown barcode
    unknownCode?.let { code ->
        AlertDialog(
            onDismissRequest = { unknownCode = null },
            title = { Text("Barcode not registered") },
            text = { Text("Barcode $code is not assigned to any item yet. Assign it to a product, colour and size?") },
            confirmButton = { TextButton(onClick = { assignCode = code; unknownCode = null }) { Text("Assign barcode") } },
            dismissButton = { TextButton(onClick = { unknownCode = null }) { Text("Cancel") } }
        )
    }

    // ---- assign: first choose a product
    assignCode?.let { code ->
        if (picker == null) ProductSearchDialog(app, products, "Assign $code to...", { assignCode = null }) { picker = it }
    }

    // ---- colour / size picker (add from search, or assign barcode)
    picker?.let { p ->
        val assigning = assignCode
        VariantPickerDialog(
            app, p, if (assigning != null) "Assign barcode" else "Add to bill",
            onDismiss = { picker = null; assignCode = null }
        ) { colour, size, qty ->
            if (assigning != null) {
                scope.launch {
                    try {
                        app.repo.assignBarcode(p.id, colour, size.size, assigning)
                        toast(ctx, "Barcode assigned")
                        picker = null; assignCode = null
                        handleCode(assigning)
                    } catch (e: Exception) { toast(ctx, e.message ?: "Failed") }
                }
            } else {
                val err = cart.add(CartLine(p.id, p.article, p.name, colour, size.size, size.barcode, qty, p.price, size.qty))
                if (err != null) toast(ctx, err) else { picker = null; query = "" }
            }
        }
    }

    if (confirmClear) ConfirmDialog("Clear bill?", "Remove all items from the current bill?", "Clear",
        { cart.clear() }, { confirmClear = false })
}

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

// ================================================================== RECEIPT
@Composable
fun ReceiptScreen(app: App, nav: Nav, billId: String) {
    val ctx = LocalContext.current
    var bill by remember { mutableStateOf<BillE?>(null) }
    LaunchedEffect(billId) { bill = app.repo.bill(billId) }
    val b = bill ?: run { EmptyState("Loading receipt..."); return }
    val items = remember(b) { parseBillItems(b.itemsJson) }
    val bmp = remember(b) { Receipt.render(app.prefs, b, items) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Image(bmp.asImageBitmap(), "Receipt", Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
        }
        Text(if (b.synced) "Saved and synced" else "Saved on this phone - will sync when online",
            color = if (b.synced) GreenOk else AmberLow, modifier = Modifier.padding(vertical = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { Receipt.share(ctx, Receipt.savePdf(ctx, bmp, b.billNo)) }, modifier = Modifier.weight(1f).height(50.dp)) {
                Icon(Icons.Filled.Share, null); Spacer(Modifier.width(4.dp)); Text("Share")
            }
            Button(onClick = { Receipt.print(ctx, bmp, "Receipt ${b.billNo}") }, modifier = Modifier.weight(1f).height(50.dp)) {
                Icon(Icons.Filled.Print, null); Spacer(Modifier.width(4.dp)); Text("Print")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            OutlinedButton(onClick = {
                val f = Receipt.savePdf(ctx, bmp, b.billNo)
                toast(ctx, "Saved: Receipt-${b.billNo}.pdf")
            }, modifier = Modifier.weight(1f).height(50.dp)) { Text("Save PDF") }
            OutlinedButton(onClick = { nav.replace(Route.Pos) }, modifier = Modifier.weight(1f).height(50.dp)) { Text("New bill") }
        }
        OutlinedButton(
            onClick = { nav.go(Route.ReturnForm("BILL", b.id)) },
            modifier = Modifier.fillMaxWidth().height(50.dp).padding(top = 8.dp)
        ) { Text("Return / Exchange") }
    }
}
