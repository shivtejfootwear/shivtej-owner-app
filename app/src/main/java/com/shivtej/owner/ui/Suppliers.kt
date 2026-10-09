@file:OptIn(ExperimentalMaterial3Api::class)

package com.shivtej.owner.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shivtej.owner.App
import com.shivtej.owner.data.*
import com.shivtej.owner.util.BillMath
import com.shivtej.owner.util.Fmt
import com.shivtej.owner.util.PaymentAmt
import com.shivtej.owner.util.Periods
import com.shivtej.owner.util.PurchaseAmt
import com.shivtej.owner.util.SupplierMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// REJECTED rows (refused by the server) stay in the lists for history but do not count in any total or balance
private fun payAmts(list: List<SupplierPaymentE>) = list.filter { it.status != "REJECTED" }.map { PaymentAmt(it.supplierId, it.billId, it.amount) }
private fun buyAmts(list: List<PurchaseE>) = list.filter { it.status != "REJECTED" }.map { PurchaseAmt(it.id, it.supplierId, it.amount) }

/** Copies the supplier's bill (a photo or a PDF up to 10 MB) into app storage. Returns the absolute path, or null if it can not be read. */
private fun importBill(ctx: android.content.Context, uri: android.net.Uri): String? {
    val mime = ctx.contentResolver.getType(uri) ?: ""
    if (mime == "application/pdf") {
        return try {
            val dir = java.io.File(ctx.filesDir, "images").apply { mkdirs() }
            val f = java.io.File(dir, java.util.UUID.randomUUID().toString() + ".pdf")
            val copied = ctx.contentResolver.openInputStream(uri)?.use { inp -> f.outputStream().use { inp.copyTo(it) } }
            if (copied == null) return null
            if (f.length() > 10L * 1024 * 1024) { f.delete(); null } else f.absolutePath
        } catch (e: Exception) { null }
    }
    // photos are resized and compressed like product photos
    return com.shivtej.owner.util.Images.importUri(ctx, uri)
}

/** Opens the bill photo / PDF: the copy on this phone first, otherwise the copy on the server. */
private fun openBill(ctx: android.content.Context, localPath: String, url: String) {
    try {
        val local = java.io.File(localPath)
        val intent = if (localPath.isNotBlank() && local.exists()) {
            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", local)
            val mime = if (local.extension.equals("pdf", ignoreCase = true)) "application/pdf" else "image/jpeg"
            android.content.Intent(android.content.Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
        }
        ctx.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        toast(ctx, "No app on this phone can open this bill")
    }
}

// ================================================================== SUPPLIERS LIST
@Composable
fun SuppliersScreen(app: App, nav: Nav) {
    val suppliers by app.repo.suppliers.collectAsStateWithLifecycle(emptyList())
    val purchases by app.repo.purchases.collectAsStateWithLifecycle(emptyList())
    val payments by app.repo.supplierPayments.collectAsStateWithLifecycle(emptyList())
    var adding by remember { mutableStateOf(false) }
    val pa = remember(purchases) { buyAmts(purchases) }
    val pay = remember(payments) { payAmts(payments) }
    val totalOwed = suppliers.sumOf { SupplierMath.totals(it.id, pa, pay).outstanding }.coerceAtLeast(0.0)

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            Card(Modifier.fillMaxWidth().padding(top = 8.dp), colors = CardDefaults.cardColors(containerColor = SoftRed)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Total still to pay suppliers", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(moneyG(app, BillMath.round2(totalOwed)), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = BrandRed)
                }
            }
            if (suppliers.isEmpty()) EmptyState("No suppliers yet. Tap + to add one.")
            LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 8.dp, bottom = 88.dp)) {
                items(suppliers, key = { it.id }) { s ->
                    val t = SupplierMath.totals(s.id, pa, pay)
                    Column(Modifier.fillMaxWidth().clickable { nav.go(Route.SupplierDetail(s.id)) }.padding(vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PersonAvatar(s.name, s.photoPath, s.photoUrl, 40.dp)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(s.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text(s.mobile.ifBlank { "No mobile" } + if (!s.active) " · INACTIVE" else "", fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Owed " + moneyG(app, t.outstanding), fontWeight = FontWeight.Bold,
                                    color = if (t.outstanding > 0) RedOut else GreenOk)
                            }
                        }
                        Text("Purchase ${moneyG(app, t.purchase)} · Paid ${moneyG(app, t.paid)}", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                    }
                    HorizontalDivider()
                }
            }
        }
        FloatingActionButton(
            onClick = { adding = true }, containerColor = BrandRed, contentColor = Color.White,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) { Icon(Icons.Filled.Add, "Add supplier") }
    }
    if (adding) SupplierDialog(app, null) { adding = false }
}

@Composable
fun SupplierDialog(app: App, s: SupplierE?, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(s?.name ?: "") }
    var mobile by remember { mutableStateOf(s?.mobile ?: "") }
    var address by remember { mutableStateOf(s?.address ?: "") }
    var notes by remember { mutableStateOf(s?.notes ?: "") }
    var active by remember { mutableStateOf(s?.active ?: true) }
    // null = photo unchanged, "" = photo removed, otherwise the path of the new picture
    var photoChange by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(if (s == null) "Add supplier" else "Edit supplier") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PhotoField("Supplier photo (optional)",
                    localPath = (photoChange ?: s?.photoPath).orEmpty(),
                    remoteUrl = if (photoChange == "") "" else s?.photoUrl.orEmpty()) { photoChange = it }
                OutlinedTextField(name, { name = it }, label = { Text("Supplier / company name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(mobile, { mobile = it.filter(Char::isDigit).take(10) }, label = { Text("Mobile (optional)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                OutlinedTextField(address, { address = it }, label = { Text("Address") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                OutlinedTextField(notes, { notes = it }, label = { Text("Notes (terms, GST no. ...)") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                if (s != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Active", modifier = Modifier.weight(1f)); Switch(checked = active, onCheckedChange = { active = it })
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                scope.launch {
                    try { app.repo.saveSupplier(s?.id, name, mobile, address, notes, active, photoChange); onDone() }
                    catch (e: Exception) { error = e.message ?: "Could not save" }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } }
    )
}

// ================================================================== SUPPLIER DETAIL
@Composable
fun SupplierDetailScreen(app: App, nav: Nav, supplierId: String) {
    val suppliers by app.repo.suppliers.collectAsStateWithLifecycle(emptyList())
    val purchases by app.repo.purchases.collectAsStateWithLifecycle(emptyList())
    val payments by app.repo.supplierPayments.collectAsStateWithLifecycle(emptyList())
    val s = suppliers.firstOrNull { it.id == supplierId }
    var tab by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf(false) }
    var paying by remember { mutableStateOf<String?>(null) }   // "" = on account, else a bill id
    var payOpen by remember { mutableStateOf(false) }
    val dateFmt = remember { SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.ENGLISH) }
    val ctx = LocalContext.current

    val myBills = purchases.filter { it.supplierId == supplierId }.sortedByDescending { it.billDate }
    val myPays = payments.filter { it.supplierId == supplierId }.sortedByDescending { it.paidAt }
    val pa = buyAmts(myBills)
    val pay = payAmts(myPays)
    val t = SupplierMath.totals(supplierId, pa, pay)

    if (s == null) { EmptyState("This supplier is not on this phone yet."); return }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
        item {
            Card(Modifier.fillMaxWidth().padding(top = 8.dp), colors = CardDefaults.cardColors(containerColor = SoftGrey)) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                            Text(s.mobile.ifBlank { "No mobile" }, fontSize = 13.sp)
                            if (s.address.isNotBlank()) Text(s.address, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (s.notes.isNotBlank()) Text(s.notes, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { editing = true }) { Icon(Icons.Filled.Edit, "Edit supplier") }
                    }
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MoneyTile("Total purchase", moneyG(app, t.purchase), Modifier.weight(1f))
                        MoneyTile("Total paid", moneyG(app, t.paid), Modifier.weight(1f))
                    }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MoneyTile("Outstanding", moneyG(app, t.outstanding), Modifier.weight(1f), if (t.outstanding > 0) RedOut else GreenOk)
                    }
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { nav.go(Route.PurchaseForm(supplierId)) }, modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = BrandRed)) { Text("New purchase bill") }
                        OutlinedButton(onClick = { paying = ""; payOpen = true }, modifier = Modifier.weight(1f)) { Text("Record payment") }
                    }
                }
            }
        }
        item {
            TabRow(selectedTabIndex = tab, modifier = Modifier.padding(top = 8.dp)) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Purchase bills (${myBills.size})") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Payments (${myPays.size})") })
            }
        }
        if (tab == 0) {
            if (myBills.isEmpty()) item { EmptyState("No purchase bills yet.") }
            items(myBills, key = { it.id }) { b ->
                val bal = SupplierMath.billBalance(PurchaseAmt(b.id, b.supplierId, b.amount), pay)
                val lines = parsePurchaseLines(b.itemsJson)
                var open by remember(b.id) { mutableStateOf(false) }
                Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(b.billNo.ifBlank { "Purchase bill" }, fontWeight = FontWeight.SemiBold)
                            Text(dateFmt.format(Date(b.billDate)) + when {
                                b.status == "REJECTED" -> " · rejected"
                                b.addStock -> " · stock added"
                                else -> " · money only"
                            }, fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(moneyG(app, b.amount), fontWeight = FontWeight.Bold)
                            if (b.status == "REJECTED") Text("REJECTED by server: ${b.rejectReason}", fontSize = 12.sp, color = RedOut)
                            else Text(if (bal > 0) "Balance ${moneyG(app, bal)}" else "Paid", fontSize = 12.sp,
                                color = if (bal > 0) RedOut else GreenOk)
                        }
                    }
                    if (bal > 0 && b.status != "REJECTED") TextButton(onClick = { paying = b.id; payOpen = true }) { Text("Pay this bill") }
                    if (open) {
                        if (lines.isEmpty()) Text("No items (amount only).", fontSize = 12.sp)
                        lines.forEach { l ->
                            Text("${l.article.ifBlank { l.name }} · ${l.colour} / ${l.size} × ${l.qty} @ ${moneyG(app, l.cost)}",
                                fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                        }
                        if (b.note.isNotBlank()) Text("Note: " + b.note, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (b.billFile.isNotBlank() || b.billFileUrl.isNotBlank()) {
                            Text("Bill photo / PDF attached", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            TextButton(onClick = { openBill(ctx, b.billFile, b.billFileUrl) }) { Text("View bill photo / PDF") }
                        }
                    }
                }
                HorizontalDivider()
            }
        } else {
            if (myPays.isEmpty()) item { EmptyState("No payments yet.") }
            items(myPays, key = { it.id }) { p ->
                val billNo = myBills.firstOrNull { it.id == p.billId }?.billNo
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(billNo?.let { "Against $it" } ?: "On account", fontWeight = FontWeight.SemiBold)
                        Text(dateFmt.format(Date(p.paidAt)) + " · " + p.method + if (p.status == "REJECTED") " · REJECTED by server, not counted" else "",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(moneyG(app, p.amount), fontWeight = FontWeight.Bold, color = if (p.status == "REJECTED") RedOut else GreenOk)
                }
                HorizontalDivider()
            }
        }
    }

    if (editing) SupplierDialog(app, s) { editing = false }
    if (payOpen) PaymentDialog(app, supplierId, t.outstanding, myBills, pay, presetBill = paying ?: "") { payOpen = false }
}

@Composable
private fun MoneyTile(title: String, value: String, modifier: Modifier, color: Color = BrandRed) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(10.dp)) {
            Text(title, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
        }
    }
}

/** Records money paid to a supplier. Against a bill it lowers that bill's balance; on account it lowers what is owed overall. */
@Composable
private fun PaymentDialog(
    app: App, supplierId: String, outstanding: Double, bills: List<PurchaseE>, pay: List<PaymentAmt>, presetBill: String, onDone: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var billId by remember { mutableStateOf(presetBill) }
    var amountText by remember { mutableStateOf("") }
    var method by remember { mutableStateOf("Cash") }
    var menu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val chosen = bills.firstOrNull { it.id == billId }
    val limit = if (chosen != null) SupplierMath.billBalance(PurchaseAmt(chosen.id, chosen.supplierId, chosen.amount), pay)
        else outstanding.coerceAtLeast(0.0)
    val amount = BillMath.parseAmount(amountText)

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Record payment") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(chosen?.let { "Bill ${it.billNo.ifBlank { "purchase" }} · balance ${moneyG(app, limit)}" } ?: "On account (total owed ${moneyG(app, outstanding.coerceAtLeast(0.0))})")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("On account") }, onClick = { billId = ""; menu = false })
                        bills.forEach { b ->
                            val bal = SupplierMath.billBalance(PurchaseAmt(b.id, b.supplierId, b.amount), pay)
                            if (bal > 0 && b.status != "REJECTED") DropdownMenuItem(text = { Text("${b.billNo.ifBlank { "Purchase" }} · balance ${moneyG(app, bal)}") },
                                onClick = { billId = b.id; menu = false })
                        }
                    }
                }
                OutlinedTextField(
                    amountText, { amountText = it.filter { c -> c.isDigit() || c == '.' }.take(10) },
                    label = { Text("Amount paid (max ${moneyG(app, limit)})") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = amountText.isNotBlank() && (amount == null || amount <= 0.0 || amount > limit + 0.005)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    listOf("Cash", "UPI", "Bank", "Cheque").forEach { m -> Chip(m, method == m) { method = m } }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving && amount != null && amount > 0.0 && amount <= limit + 0.005, onClick = {
                if (saving) return@TextButton // a second tap must not record a second payment
                saving = true
                scope.launch {
                    try {
                        app.repo.addSupplierPayment(supplierId, billId, amount!!, method, "")
                        onDone()
                    } catch (e: Exception) { error = e.message ?: "Could not save"; saving = false }
                }
            }) { Text(if (saving) "Saving..." else "Save payment") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } }
    )
}

// ================================================================== NEW PURCHASE BILL
@Composable
fun PurchaseFormScreen(app: App, nav: Nav, supplierId: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val suppliers by app.repo.suppliers.collectAsStateWithLifecycle(emptyList())
    val products by app.repo.products.collectAsStateWithLifecycle(emptyList())
    val s = suppliers.firstOrNull { it.id == supplierId }
    var billNo by remember { mutableStateOf("") }
    var dateText by remember { mutableStateOf(SimpleDateFormat("dd-MM-yyyy", Locale.US).format(Date())) }
    var addStock by remember { mutableStateOf(true) }
    var note by remember { mutableStateOf("") }
    var amountOnly by remember { mutableStateOf("") }
    var paidText by remember { mutableStateOf("") }
    var method by remember { mutableStateOf("Cash") }
    val lines = remember { mutableStateListOf<PurchaseLine>() }
    var pickProduct by remember { mutableStateOf(false) }
    var pickedProduct by remember { mutableStateOf<Product?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // the supplier's bill photo or PDF (a copy kept on this phone and sent with the bill)
    var billPath by remember { mutableStateOf("") }
    var billBusy by remember { mutableStateOf(false) }
    val billPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            billBusy = true
            scope.launch {
                val path = withContext(Dispatchers.IO) { importBill(ctx, uri) }
                billBusy = false
                if (path == null) error = "This bill can not be read. Choose a photo (JPG/PNG) or a PDF under 10 MB."
                else { billPath = path; error = null }
            }
        }
    }

    val date = Periods.parseDay(dateText)
    val itemsTotal = BillMath.round2(lines.sumOf { it.cost * it.qty })
    val typedAmount = BillMath.parseAmount(amountOnly)
    val total = if (lines.isNotEmpty()) itemsTotal else (typedAmount ?: 0.0)
    val paid = BillMath.parseAmount(paidText) ?: 0.0

    if (s == null) { EmptyState("Supplier not found."); return }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Purchase from ${s.name}", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(billNo, { billNo = it.take(30) }, label = { Text("Supplier bill no.") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(dateText, { dateText = it.take(10) }, label = { Text("Bill date dd-mm-yyyy") }, singleLine = true,
                isError = date == null, modifier = Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Add items to stock", fontWeight = FontWeight.SemiBold)
                Text(if (addStock) "Quantities below go into your inventory now." else "Money entry only - stock is not changed.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = addStock, onCheckedChange = { addStock = it })
        }

        Text("Items", fontWeight = FontWeight.SemiBold)
        if (lines.isEmpty()) Text("No items added. Enter the bill amount below, or add the items you received.", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        lines.forEachIndexed { i, l ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${l.article.ifBlank { l.name }} · ${l.colour} / ${l.size}", fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text("${l.qty} pcs × ${moneyG(app, l.cost)} = ${moneyG(app, BillMath.round2(l.qty * l.cost))}", fontSize = 12.sp)
                    }
                    IconButton(onClick = { lines.removeAt(i) }) { Icon(Icons.Filled.Delete, "Remove") }
                }
            }
        }
        OutlinedButton(onClick = { pickProduct = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Add item")
        }

        if (lines.isEmpty()) OutlinedTextField(amountOnly, { amountOnly = it.filter { c -> c.isDigit() || c == '.' }.take(10) },
            label = { Text("Bill amount") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))

        OutlinedTextField(note, { note = it.take(200) }, label = { Text("Note (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())

        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SoftGrey)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Supplier's bill photo or PDF", fontWeight = FontWeight.SemiBold)
                Text(if (billPath.isBlank()) "Optional. Attach the bill so it stays with this supplier."
                     else if (billPath.endsWith(".pdf")) "PDF attached - it is sent with the bill when you save."
                     else "Photo attached - it is sent with the bill when you save.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { billPicker.launch(arrayOf("image/*", "application/pdf")) }, enabled = !billBusy) {
                        Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp))
                        Text(if (billBusy) "Reading..." else if (billPath.isBlank()) "Choose photo / PDF" else "Change")
                    }
                    if (billPath.isNotBlank()) TextButton(onClick = { billPath = "" }) { Text("Remove") }
                }
            }
        }

        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SoftGrey)) {
            Column(Modifier.padding(12.dp)) {
                Row { Text("Bill total", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Text(moneyG(app, total), fontWeight = FontWeight.Bold, color = BrandRed) }
                OutlinedTextField(paidText, { paidText = it.filter { c -> c.isDigit() || c == '.' }.take(10) },
                    label = { Text("Paid now (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Cash", "UPI", "Bank", "Cheque").forEach { m -> Chip(m, method == m) { method = m } }
                }
                Row { Text("Balance after this bill", modifier = Modifier.weight(1f)); Text(moneyG(app, BillMath.balance(total, paid)), fontWeight = FontWeight.Bold) }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
        Button(
            enabled = !busy && total > 0.0 && date != null && (paidText.isBlank() || paid <= total + 0.005),
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = BrandRed),
            onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        app.repo.createPurchase(
                            supplierId = supplierId, billNo = billNo, billDate = date!!, lines = lines.toList(),
                            amountIfNoItems = typedAmount ?: 0.0, addStock = addStock, note = note,
                            paidNow = paid, method = method, billFile = billPath
                        )
                        nav.back()
                    } catch (e: Exception) {
                        error = e.message ?: "Could not save the purchase bill"
                    } finally { busy = false }
                }
            }
        ) { Text(if (busy) "Saving..." else "Save purchase bill", fontWeight = FontWeight.Bold) }
    }

    if (pickProduct) ProductSearchDialog(app, products, "Choose product", { pickProduct = false }) { p ->
        pickProduct = false; pickedProduct = p
    }
    pickedProduct?.let { p ->
        PurchaseLinePicker(app, p, onDismiss = { pickedProduct = null }) { line ->
            lines.add(line); pickedProduct = null
        }
    }
}

/** Colour, size, quantity and cost for one line of a purchase bill. Every size is offered (stock can be 0). */
@Composable
private fun PurchaseLinePicker(app: App, p: Product, onDismiss: () -> Unit, onAdd: (PurchaseLine) -> Unit) {
    var colour by remember(p.id) { mutableStateOf(p.variants.firstOrNull()?.colour ?: "") }
    var size by remember(p.id) { mutableStateOf<String?>(null) }
    var qty by remember(p.id) { mutableIntStateOf(1) }
    var costText by remember(p.id) { mutableStateOf("") }
    val variant = p.variants.firstOrNull { it.colour == colour }
    val cost = BillMath.parseAmount(costText)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${p.article} · ${p.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Colour", fontWeight = FontWeight.SemiBold)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    p.variants.forEach { v -> Chip(v.colour, colour == v.colour) { colour = v.colour; size = null } }
                }
                Text("Size", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    variant?.sizes?.forEach { s -> Chip("${s.size} (have ${s.qty})", size == s.size) { size = s.size } }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Qty")
                    IconButton(onClick = { if (qty > 1) qty-- }) { Icon(Icons.Filled.Remove, "Less") }
                    Text("$qty", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    IconButton(onClick = { qty++ }) { Icon(Icons.Filled.Add, "More") }
                }
                OutlinedTextField(costText, { costText = it.filter { c -> c.isDigit() || c == '.' }.take(10) },
                    label = { Text("Cost per pair") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
        },
        confirmButton = {
            TextButton(enabled = size != null && cost != null && cost > 0.0, onClick = {
                onAdd(PurchaseLine(p.id, p.article, p.name, colour, size!!, qty, cost!!))
            }) { Text("Add to bill") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
