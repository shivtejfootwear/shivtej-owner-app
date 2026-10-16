@file:OptIn(ExperimentalMaterial3Api::class)

package com.shivtej.owner.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import com.shivtej.owner.util.BillMath
import com.shivtej.owner.util.BillTheme
import com.shivtej.owner.util.Fmt
import com.shivtej.owner.util.Invoice
import com.shivtej.owner.util.Ledger
import com.shivtej.owner.util.MathLine
import com.shivtej.owner.util.Receipt
import com.shivtej.owner.util.ReceiptData
import com.shivtej.owner.util.ThermalText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// ================================================================== POS BILLING
@Composable
fun PosScreen(app: App, nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val products by app.repo.products.collectAsStateWithLifecycle(emptyList())
    val staff by app.repo.staff.collectAsStateWithLifecycle(emptyList())
    val posCustomers by app.repo.posCustomers.collectAsStateWithLifecycle(emptyList())
    val webCustomers by app.repo.customers.collectAsStateWithLifecycle(emptyList())
    val bills by app.repo.bills.collectAsStateWithLifecycle(emptyList())
    val cart = app.cart
    val draft = cart.draft
    val withCamera = rememberWithCamera()

    var query by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var unknownCode by remember { mutableStateOf<String?>(null) }
    var assignCode by remember { mutableStateOf<String?>(null) }
    var picker by remember { mutableStateOf<Product?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    // The Bluetooth scanner (Netum NT-1228BL, keyboard mode) types the code into whichever box has focus.
    // The search box keeps focus while no dialog is open, so a scan always lands there.
    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val idle = unknownCode == null && assignCode == null && picker == null && !scanning
    LaunchedEffect(idle) { if (idle) { searchFocus.requestFocus(); keyboard?.hide() } }

    /** A scanned piece goes into the bill at once: one piece, no popup, no size or quantity to choose. */
    fun handleCode(code: String) {
        val c = code.trim()
        query = ""   // the box is empty for the next scan
        if (c.isEmpty()) return
        scope.launch {
            val h = app.repo.lookup(c)
            when {
                h == null -> unknownCode = c
                cart.lines.any { line -> c in line.barcode.split(",") } -> toast(ctx, "This piece is already in the bill")
                else -> {
                    val err = cart.add(CartLine(h.product.id, h.product.article, h.product.name, h.colour, h.size.size,
                        c, 1, maxOf(h.product.mrp, h.product.price), h.size.qty, h.product.mrp))
                    if (err != null) toast(ctx, err) else toast(ctx, "Added: ${h.product.article} ${h.size.size}")
                }
            }
        }
    }

    /** Enter or the search icon: only a barcode (digits) is looked up and added. Typed words just filter the list below. */
    fun submitSearch() {
        val t = query.trim()
        if (t.length >= 6 && t.all { it.isDigit() }) handleCode(t)
    }

    val results = if (query.isBlank()) emptyList() else {
        val q = query.trim().lowercase()
        products.filter { p ->
            p.active && (listOf(p.name, p.article, p.brand, p.category).any { it.lowercase().contains(q) } ||
                p.variants.any { v -> v.colour.lowercase().contains(q) || v.sizes.any { s ->
                    (listOf(s.barcode) + s.barcodes).any { it.lowercase() == q } } })
        }.take(20)
    }

    // ---- the numbers of this bill (all arithmetic is in BillMath)
    val taxPct = if (draft.taxText.isBlank()) app.prefs.taxPercent else (BillMath.parseAmount(draft.taxText) ?: 0.0)
    val totals = BillMath.compute(cart.lines.map { MathLine(it.mrp, it.price, it.qty) }, taxPct)
    val paidTyped = if (draft.paidText.isBlank()) null else BillMath.parseAmount(draft.paidText)
    val paidNow = paidTyped ?: totals.grandTotal
    val balance = BillMath.balance(totals.grandTotal, paidNow)
    val shop = BillMeta.currentShop(app.prefs)

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // ---------------- search / scan
            item {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { v ->
                            query = v
                            val t = v.trim()
                            if (t.length >= 6 && t.none { it.isWhitespace() })
                                scope.launch { if (app.repo.isKnownBarcode(t)) handleCode(t) }
                        },
                        singleLine = true,
                        placeholder = { Text("Search product / type barcode") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submitSearch() }),
                        trailingIcon = {
                            IconButton(onClick = { submitSearch() }) { Icon(Icons.Filled.Search, "Find barcode") }
                        },
                        modifier = Modifier.weight(1f).focusRequester(searchFocus)
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton(
                        onClick = { withCamera { scanning = true } }, modifier = Modifier.size(56.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = BrandRed)
                    ) { Icon(Icons.Filled.QrCodeScanner, "Scan") }
                }
            }
            if (results.isNotEmpty()) item {
                Card(Modifier.fillMaxWidth()) {
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

            // ---------------- customer + bill info
            item {
                CustomerCard(app, nav, posCustomers, webCustomers, bills)
            }
            item {
                BillInfoCard(app, staff)
            }

            // ---------------- items
            item { SectionTitle("Items (${cart.lines.size})") }
            if (cart.lines.isEmpty()) item { EmptyState("Scan a barcode or search a product to start a bill.") }
            items(cart.lines.size, key = { i -> cart.lines.getOrNull(i)?.let { it.productId + "|" + it.colour + "|" + it.size } ?: "x$i" }) { i ->
                val l = cart.lines.getOrNull(i)
                if (l != null) CartLineCard(app, i, l)
            }

            // ---------------- totals
            if (cart.lines.isNotEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SoftGrey)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            TotalRow("Total MRP", moneyG(app, totals.totalMrp))
                            TotalRow("Total Discount", "-" + moneyG(app, totals.totalDiscount), GreenOk)
                            TotalRow("Sub Total (after discount)", moneyG(app, totals.subTotal), bold = true)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("GST / Tax", modifier = Modifier.weight(1f))
                                OutlinedTextField(
                                    value = draft.taxText, onValueChange = { v ->
                                        cart.updateDraft { it.copy(taxText = v.filter { c -> c.isDigit() || c == '.' }.take(5)) }
                                    },
                                    singleLine = true, modifier = Modifier.width(110.dp),
                                    placeholder = { Text(BillMath.plain(app.prefs.taxPercent)) },
                                    suffix = { Text("%") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                                )
                                Text(moneyG(app, totals.taxAmount), fontWeight = FontWeight.SemiBold, modifier = Modifier.width(96.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.End)
                            }
                            HorizontalDivider()
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Grand Total", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                Text(moneyG(app, totals.grandTotal), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = BrandRed)
                            }
                        }
                    }
                }

                // ---------------- payment
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text("Payment", fontWeight = FontWeight.Bold)
                            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("Cash", "UPI", "Card", "Other").forEach { m ->
                                    Chip(m, draft.method == m) { cart.updateDraft { it.copy(method = m) } }
                                }
                            }
                            OutlinedTextField(
                                value = draft.paidText, onValueChange = { v ->
                                    cart.updateDraft { it.copy(paidText = v.filter { c -> c.isDigit() || c == '.' }.take(10)) }
                                },
                                singleLine = true, modifier = Modifier.fillMaxWidth(),
                                label = { Text("Amount paid (empty = full ${moneyG(app, totals.grandTotal)})") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                isError = draft.paidText.isNotBlank() && paidTyped == null
                            )
                            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Balance", modifier = Modifier.weight(1f))
                                Text(moneyG(app, balance), fontWeight = FontWeight.Bold, fontSize = 18.sp,
                                    color = if (balance <= 0.0) GreenOk else RedOut)
                            }
                        }
                    }
                }
                if (shop.notes.isNotEmpty()) item {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SoftGrey)) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Notes / Return policy (printed on the bill)", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), fontSize = 14.sp)
                                TextButton(onClick = { nav.go(Route.ShopSettings) }) { Text("Edit") }
                            }
                            shop.notes.forEach { Text("• $it", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }

        // ---------------- sticky bottom bar
        HorizontalDivider()
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { confirmClear = true }, enabled = (cart.lines.isNotEmpty() || draft.customerMobile.isNotBlank()) && !busy,
                    modifier = Modifier.weight(1f).height(54.dp)
                ) { Text("Clear") }
                Button(
                    enabled = cart.lines.isNotEmpty() && !busy,
                    modifier = Modifier.weight(2.4f).height(54.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrandRed),
                    onClick = {
                        if (busy) return@Button // a second tap must not record a second bill
                        error = null
                        val mob = draft.customerMobile
                        val bad: String? = when {
                            mob.isNotBlank() && !BillMath.isValidMobile(mob) -> "Customer mobile number must be 10 digits (or leave it empty)."
                            cart.lines.any { it.price <= 0.0 } -> "Selling rate must be more than 0 for every item."
                            draft.taxText.isNotBlank() && BillMath.parseAmount(draft.taxText) == null -> "GST % is not a number."
                            draft.paidText.isNotBlank() && paidTyped == null -> "Amount paid is not a number."
                            paidTyped != null && paidTyped < 0.0 -> "Amount paid can not be negative."
                            paidTyped != null && paidTyped > totals.grandTotal + 0.005 -> "Amount paid is more than the bill total."
                            else -> null
                        }
                        if (bad != null) { error = bad } else {
                            busy = true
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
                                        val d = cart.draft
                                        val extra = BillExtra(
                                            salesPersonId = d.salesPersonId, salesPerson = d.salesPersonName,
                                            customerName = d.customerName, customerMobile = d.customerMobile,
                                            customerAddress = d.customerAddress, taxPercent = taxPct, amountPaid = paidTyped
                                        )
                                        val bill = app.repo.createBill(cart.lines.toList(), d.method, extra)
                                        cart.clear()
                                        nav.replace(Route.Receipt(bill.id))
                                    }
                                } catch (e: Exception) {
                                    error = e.message ?: "Could not complete the bill"
                                } finally { busy = false }
                            }
                        }
                    }
                ) {
                    Text(
                        if (busy) "Please wait..." else if (cart.lines.isEmpty()) "Complete bill"
                        else "Complete bill · " + moneyG(app, totals.grandTotal),
                        fontSize = 16.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    // ---- scanner
    if (scanning) ScannerDialog(onResult = { scanning = false; handleCode(it) }, onDismiss = { scanning = false })

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
                val err = cart.add(CartLine(p.id, p.article, p.name, colour, size.size, size.barcode, qty, maxOf(p.mrp, p.price), size.qty, p.mrp))
                if (err != null) toast(ctx, err) else { picker = null; query = "" }
            }
        }
    }

    if (confirmClear) ConfirmDialog("Clear bill?", "Remove all items and the customer details from the current bill?", "Clear",
        { cart.clear(); cart.updateDraft { BillDraft(salesPersonId = it.salesPersonId, salesPersonName = it.salesPersonName, method = it.method) } },
        { confirmClear = false })
}

@Composable
private fun TotalRow(label: String, value: String, color: Color = Color.Unspecified, bold: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
        Text(value, fontWeight = FontWeight.SemiBold, color = color)
    }
}

/** One item of the bill: quantity, MRP, the selling rate (editable) and the automatic discount. */
@Composable
private fun CartLineCard(app: App, i: Int, l: CartLine) {
    val ctx = LocalContext.current
    val cart = app.cart
    var rateText by remember(l.productId, l.colour, l.size) { mutableStateOf(BillMath.plain(l.price)) }
    val mrp = BillMath.lineMrp(l.mrp, l.price)
    val discount = BillMath.lineDiscount(l.mrp, l.price, l.qty)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(l.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text("${l.article} · ${l.colour} / Size ${l.size}", fontSize = 12.sp)
                    Text("Barcode ${l.barcode.ifBlank { "-" }} · in stock ${l.maxQty}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { cart.remove(i) }) { Icon(Icons.Filled.Delete, "Remove") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { cart.change(i, -1) }) { Icon(Icons.Filled.Remove, "Less") }
                Text("${l.qty}", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                IconButton(onClick = { cart.change(i, 1)?.let { toast(ctx, it) } }) { Icon(Icons.Filled.Add, "More") }
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text("MRP ${moneyG(app, mrp)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = rateText,
                    onValueChange = { v ->
                        val f = v.filter { c -> c.isDigit() || c == '.' }.take(9)
                        rateText = f
                        // a cleared or invalid rate must not keep the old price in the cart (Complete bill then refuses it)
                        cart.setPrice(i, f.toDoubleOrNull() ?: 0.0)
                    },
                    label = { Text("Rate") }, singleLine = true, modifier = Modifier.width(112.dp),
                    isError = rateText.toDoubleOrNull() == null || (rateText.toDoubleOrNull() ?: 0.0) <= 0.0,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
            }
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (discount > 0.0) Text("Discount ${moneyG(app, discount)}", fontSize = 12.sp, color = GreenOk, modifier = Modifier.weight(1f))
                else Spacer(Modifier.weight(1f))
                Text("Amount ${moneyG(app, BillMath.round2(l.price * l.qty))}", fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Customer name / mobile / city. Matching saved customers appear under the fields as you type; choosing one loads everything. */
@Composable
private fun CustomerCard(
    app: App, nav: Nav, posCustomers: List<PosCustomerE>, webCustomers: List<CustomerE>, bills: List<BillE>
) {
    val cart = app.cart
    val d = cart.draft
    val m = d.customerMobile
    var autoLoaded by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    val pays by app.repo.customerPayments.collectAsStateWithLifecycle(emptyList())
    val orderRows by app.repo.orders.collectAsStateWithLifecycle(emptyList())
    val returnRows by app.repo.returns.collectAsStateWithLifecycle(emptyList())
    val saved = if (m.length == 10) posCustomers.firstOrNull { it.mobile == m } else null
    val web = if (m.length == 10 && saved == null) webCustomers.firstOrNull { BillMath.normMobile(it.mobile) == m } else null
    val past = if (m.length == 10) bills.filter { it.customerMobile == m }.sortedByDescending { it.createdAt } else emptyList()
    // what the customer owes BEFORE this bill (the bill being made is not saved yet)
    val owed = if (m.length == 10 && (saved != null || web != null || past.isNotEmpty())) {
        Ledger.summaryOf(m, bills, pays, ledgerOrders(orderRows), ledgerReturns(returnRows), openingsOf(posCustomers))
    } else null
    // suggestions while the owner types; hidden once a full saved number has been found
    val matches = if (!typing || (m.length == 10 && (saved != null || web != null))) emptyList()
        else customerMatches(m, d.customerName, posCustomers, webCustomers)

    LaunchedEffect(m, saved?.name, saved?.address, web?.name) {
        if (m.length == 10 && (saved != null || web != null)) {
            val name = saved?.name?.ifBlank { null } ?: web?.name?.ifBlank { null } ?: ""
            val addr = saved?.address ?: ""
            if (autoLoaded || (cart.draft.customerName.isBlank() && cart.draft.customerAddress.isBlank())) {
                cart.updateDraft { it.copy(customerName = name, customerAddress = addr) }
                autoLoaded = true
            }
        } else if (m.length != 10 && autoLoaded) {
            cart.updateDraft { it.copy(customerName = "", customerAddress = "") }
            autoLoaded = false
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Customer Details", fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = d.customerMobile,
                // pasted numbers such as "+91 98765 43210" are reduced to the 10 digits
                onValueChange = { v -> typing = true; cart.updateDraft { it.copy(customerMobile = BillMath.normMobile(v)) } },
                label = { Text("Mobile number") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Filled.Phone, null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                isError = m.isNotEmpty() && m.length != 10,
                supportingText = { if (m.isNotEmpty() && m.length != 10) Text("Mobile number must be 10 digits") }
            )
            OutlinedTextField(
                value = d.customerName, onValueChange = { v -> autoLoaded = false; typing = true; cart.updateDraft { it.copy(customerName = v) } },
                label = { Text("Customer name (empty = Walk-in Customer)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Filled.Person, null) }
            )
            if (matches.isNotEmpty()) {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SoftRed)) {
                    Column {
                        Text("Saved customers that match. Tap one to load it.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp))
                        matches.forEach { c ->
                            Row(Modifier.fillMaxWidth().clickable {
                                typing = false; autoLoaded = true
                                cart.updateDraft { it.copy(customerMobile = c.mobile, customerName = c.name, customerAddress = c.address) }
                            }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(c.name.ifBlank { "Customer" }, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    Text(c.mobile + if (c.address.isNotBlank()) " · " + c.address else "", fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                }
                                Text("Select", color = BrandRed, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            OutlinedTextField(
                value = d.customerAddress, onValueChange = { v -> autoLoaded = false; cart.updateDraft { it.copy(customerAddress = v) } },
                label = { Text("City / Address") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Filled.LocationOn, null) }
            )
            if (m.length == 10) {
                if (saved != null || web != null || past.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Saved customer · ${past.size} earlier bill(s)" + if (past.isNotEmpty()) " · " + moneyG(app, past.sumOf { it.total }) else "",
                            fontSize = 12.sp, color = GreenOk, modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { nav.go(Route.CustomerDetail(m)) }) { Text("History") }
                    }
                    owed?.let { s ->
                        Text(
                            if (s.outstanding > 0.0) "Owes now (before this bill): ${moneyG(app, s.outstanding)}" else "No balance due before this bill",
                            fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (s.outstanding > 0.0) RedOut else GreenOk
                        )
                    }
                    past.take(3).forEach { b ->
                        Row(Modifier.fillMaxWidth().clickable { nav.go(Route.Receipt(b.id)) }.padding(vertical = 2.dp)) {
                            Text(b.billNo + " · " + SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).format(Date(b.createdAt)),
                                fontSize = 12.sp, modifier = Modifier.weight(1f))
                            Text(moneyG(app, b.total), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                } else {
                    Text("New customer - will be saved with this bill.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private data class CustomerPick(val mobile: String, val name: String, val address: String)

/** Saved customers whose name contains the text typed, or whose mobile contains the digits typed (spaces and +91 ignored). */
private fun customerMatches(mobileText: String, nameText: String, posCustomers: List<PosCustomerE>, webCustomers: List<CustomerE>): List<CustomerPick> {
    val digits = mobileText.filter(Char::isDigit)
    val name = nameText.trim()
    if (digits.isEmpty() && name.length < 2) return emptyList()
    val all = LinkedHashMap<String, CustomerPick>()
    posCustomers.forEach { c -> val mm = BillMath.normMobile(c.mobile); if (mm.length == 10) all[mm] = CustomerPick(mm, c.name, c.address) }
    webCustomers.forEach { c -> val mm = BillMath.normMobile(c.mobile); if (mm.length == 10 && mm !in all) all[mm] = CustomerPick(mm, c.name, "") }
    // "919..." typed with the country code also finds the 10 digit number after it
    val alt = if (digits.length > 2 && digits.startsWith("91")) digits.drop(2) else null
    return all.values.filter { c ->
        (digits.isNotEmpty() && (c.mobile.contains(digits) || (alt != null && c.mobile.contains(alt)))) ||
            (name.length >= 2 && c.name.contains(name, ignoreCase = true))
    }.sortedBy { it.name.lowercase() }.take(5)
}

/** Bill number, date and the sales person who made this sale. */
@Composable
private fun BillInfoCard(app: App, staff: List<StaffE>) {
    val cart = app.cart
    val d = cart.draft
    val active = staff.filter { it.active }
    var open by remember { mutableStateOf(false) }
    val now = remember { System.currentTimeMillis() }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row {
                Column(Modifier.weight(1f)) {
                    Text("Bill No.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(app.repo.peekBillNo(), fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1.3f)) {
                    Text("Date & time", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(Fmt.dateTime(now), fontWeight = FontWeight.Bold)
                }
            }
            Text("Sales person", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box {
                OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Badge, null)
                    Spacer(Modifier.width(8.dp))
                    Text(d.salesPersonName.ifBlank { "Choose sales person" }, modifier = Modifier.weight(1f))
                    Icon(Icons.Filled.ArrowDropDown, null)
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    if (active.isEmpty()) DropdownMenuItem(text = { Text("No staff yet - add them in Staff") }, onClick = { open = false })
                    active.forEach { s ->
                        DropdownMenuItem(text = { Text(s.name) }, onClick = {
                            cart.updateDraft { it.copy(salesPersonId = s.id, salesPersonName = s.name) }
                            open = false
                        })
                    }
                    if (d.salesPersonId.isNotBlank()) DropdownMenuItem(text = { Text("No sales person") }, onClick = {
                        cart.updateDraft { it.copy(salesPersonId = "", salesPersonName = "") }
                        open = false
                    })
                }
            }
            if (d.salesPersonId.isBlank()) Text(
                "No sales person selected - this sale will not count for any staff member.",
                fontSize = 12.sp, color = AmberLow
            )
        }
    }
}

// ================================================================== RECEIPT
private class Rendered(val data: ReceiptData?, val theme: BillTheme?, val bmp: Bitmap)

@Composable
fun ReceiptScreen(app: App, nav: Nav, billId: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var bill by remember { mutableStateOf<BillE?>(null) }
    LaunchedEffect(billId) { bill = app.repo.bill(billId) }
    val pays by app.repo.customerPayments.collectAsStateWithLifecycle(emptyList())
    val b = bill ?: run { EmptyState("Loading receipt..."); return }

    // the picture is drawn off the main thread; if the new design fails for any reason the plain receipt is used
    val rendered by produceState<Rendered?>(null, b) {
        value = withContext(Dispatchers.Default) {
            try {
                val (data, theme) = BillMeta.buildReceipt(app.prefs, b)
                Rendered(data, theme, Invoice.invoice(ctx, data, theme))
            } catch (e: Exception) {
                Rendered(null, null, Receipt.render(app.prefs, b, parseBillItems(b.itemsJson)))
            }
        }
    }
    val r = rendered ?: run { EmptyState("Preparing the bill..."); return }
    if (!b.deleted) SaveCustomerOffer(app, b)

    var printing by remember { mutableStateOf(false) }
    var printMsg by remember { mutableStateOf<String?>(null) }
    var printOk by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var askRestore by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }
    val withBt = rememberWithBluetooth(app)

    fun printBill() {
        withBt {
            printing = true; printMsg = null
            scope.launch {
                val res = try {
                    val data = r.data
                    val theme = r.theme
                    if (data == null || theme == null) {
                        app.printer.printBitmap(r.bmp)
                    } else if (app.prefs.printMode == "text") {
                        app.printer.printText(ThermalText.build(data))
                    } else {
                        val thermal = withContext(Dispatchers.Default) { Invoice.thermal(ctx, data, theme) }
                        app.printer.printBitmap(thermal)
                    }
                } catch (e: Exception) { Result.failure<Unit>(e) }
                printing = false
                printOk = res.isSuccess
                printMsg = if (res.isSuccess) "Sent to printer." else (res.exceptionOrNull()?.message ?: "Could not print.")
            }
        }
    }

    // Restore: the bill counts again, its items leave stock again (refused, with nothing changed, if stock is short)
    if (askRestore) {
        AlertDialog(
            onDismissRequest = { askRestore = false },
            title = { Text("बिल ${b.billNo} रिस्टोर करें?") },
            text = {
                Text("सामान फिर से स्टॉक से घटेगा और ग्राहक के पैसे फिर हिसाब में जुड़ेंगे। अगर किसी आइटम का स्टॉक कम है, तो रिस्टोर नहीं होगा।",
                    fontSize = 13.sp)
            },
            confirmButton = {
                TextButton(onClick = {
                    askRestore = false
                    restoring = true
                    scope.launch {
                        try {
                            app.repo.restoreBill(b.id)
                            toast(ctx, "बिल रिस्टोर हुआ")
                            nav.back()
                        } catch (e: Exception) {
                            restoring = false
                            toast(ctx, e.message ?: "बिल रिस्टोर नहीं हो सका")
                        }
                    }
                }) { Text("रिस्टोर", color = GreenOk, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { askRestore = false }) { Text("रद्द") } }
        )
    }

    // Delete = full reversal: stock goes back, the customer's payments on this bill are reversed (see Repo.deleteBill)
    if (askDelete) {
        val received = b.paid + pays.filter { it.billId == b.id }.sumOf { it.amount }
        AlertDialog(
            onDismissRequest = { askDelete = false },
            title = { Text("बिल ${b.billNo} डिलीट करें?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("सामान स्टॉक में वापस जुड़ जाएगा (वही प्रोडक्ट, कलर और साइज़, बारकोड के साथ)।", fontSize = 13.sp)
                    if (received > 0.0) Text("ग्राहक से इस बिल पर मिले पैसे (${money(app, received)}) हिसाब से हट जाएँगे। अगर वो पैसे ग्राहक को लौटाने हैं तो आप खुद लौटाएँ।", fontSize = 13.sp)
                    Text("यह सर्वर पर भी डिलीट होगा। यह वापस नहीं हो सकता।", fontSize = 13.sp, color = RedOut)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    askDelete = false
                    deleting = true
                    scope.launch {
                        try {
                            val notBack = app.repo.deleteBill(b.id)
                            toast(ctx, if (notBack == 0) "बिल डिलीट हुआ, सामान स्टॉक में वापस जुड़ गया"
                                else "बिल डिलीट हुआ। $notBack आइटम का स्टॉक वापस नहीं जुड़ सका (प्रोडक्ट अब मौजूद नहीं है)")
                            nav.back()
                        } catch (e: Exception) {
                            deleting = false
                            toast(ctx, e.message ?: "बिल डिलीट नहीं हो सका")
                        }
                    }
                }) { Text("डिलीट", color = RedOut, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { askDelete = false }) { Text("रद्द") } }
        )
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        if (b.deleted) {
            Text("यह बिल डिलीट है", color = RedOut, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
            Button(
                onClick = { askRestore = true }, enabled = !restoring,
                colors = ButtonDefaults.buttonColors(containerColor = GreenOk),
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Icon(Icons.Filled.Restore, null); Spacer(Modifier.width(6.dp))
                Text("रिस्टोर", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            OutlinedButton(
                onClick = { askDelete = true }, enabled = !deleting,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = RedOut),
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Icon(Icons.Filled.Delete, null); Spacer(Modifier.width(6.dp))
                Text("डिलीट", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
        Card(Modifier.fillMaxWidth().padding(top = 8.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Image(r.bmp.asImageBitmap(), "Receipt", Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
        }
        Text(if (b.synced) "Saved and synced" else "Saved on this phone - will sync when online",
            color = if (b.synced) GreenOk else AmberLow, modifier = Modifier.padding(vertical = 8.dp))

        Button(
            onClick = { printBill() }, enabled = !printing,
            colors = ButtonDefaults.buttonColors(containerColor = BrandRed),
            modifier = Modifier.fillMaxWidth().height(54.dp)
        ) {
            Icon(Icons.Filled.Print, null); Spacer(Modifier.width(8.dp))
            Text(if (printing) "Printing..." else "Print Bill (Bluetooth printer)", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        printMsg?.let {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(it, color = if (printOk) GreenOk else RedOut, fontSize = 13.sp, modifier = Modifier.weight(1f))
                if (!printOk) TextButton(onClick = { nav.go(Route.Printer) }) { Text("Printer") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Button(onClick = { Receipt.share(ctx, Receipt.savePdf(ctx, r.bmp, b.billNo)) }, modifier = Modifier.weight(1f).height(50.dp)) {
                Icon(Icons.Filled.Share, null); Spacer(Modifier.width(4.dp)); Text("Share PDF")
            }
            Button(onClick = {
                val text = "Bill ${b.billNo} - ${Fmt.moneyG(app.prefs.currency.ifBlank { "₹" }, b.total)}. Thank you for shopping with " +
                    app.prefs.storeName + "."
                Receipt.shareWhatsApp(ctx, Receipt.savePdf(ctx, r.bmp, b.billNo), text, BillMath.normMobile(b.customerMobile))
            }, modifier = Modifier.weight(1f).height(50.dp)) { Text("WhatsApp") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            OutlinedButton(onClick = { Receipt.print(ctx, r.bmp, "Receipt ${b.billNo}") }, modifier = Modifier.weight(1f).height(50.dp)) {
                Icon(Icons.Filled.Print, null); Spacer(Modifier.width(4.dp)); Text("Print (other)")
            }
            OutlinedButton(onClick = {
                Receipt.savePdf(ctx, r.bmp, b.billNo)
                toast(ctx, "Saved: Receipt-${b.billNo}.pdf")
            }, modifier = Modifier.weight(1f).height(50.dp)) { Text("Save PDF") }
        }
        OutlinedButton(onClick = { nav.replace(Route.Pos) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(50.dp)) { Text("New bill") }
        OutlinedButton(
            onClick = { nav.go(Route.ReturnForm("BILL", b.id)) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(50.dp)
        ) { Text("Return / Exchange") }
    }
}

/** After a bill for a mobile number that is not saved yet: offer to save the customer (name and address can be changed first). */
@Composable
fun SaveCustomerOffer(app: App, bill: BillE) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var isNew by remember(bill.id) { mutableStateOf(false) }
    var dismissed by remember(bill.id) { mutableStateOf(false) }
    var name by remember(bill.id) { mutableStateOf(bill.customerName) }
    var address by remember(bill.id) { mutableStateOf(bill.customerAddress) }
    LaunchedEffect(bill.id) { isNew = app.repo.isNewShopCustomer(bill.customerMobile) }
    if (!isNew || dismissed) return
    AlertDialog(
        onDismissRequest = { dismissed = true },
        title = { Text("Save this customer?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Mobile " + BillMath.normMobile(bill.customerMobile) + ". Saved customers are found next time you type their name or number.",
                    fontSize = 13.sp)
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(address, { address = it }, label = { Text("City / Address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                scope.launch {
                    try {
                        app.repo.saveCustomer(bill.customerMobile, name, address)
                        isNew = false
                        toast(ctx, "Customer saved")
                    } catch (e: Exception) { toast(ctx, e.message ?: "Could not save the customer") }
                }
            }) { Text("Save customer") }
        },
        dismissButton = { TextButton(onClick = { dismissed = true }) { Text("Not now") } }
    )
}
