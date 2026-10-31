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
import java.util.Calendar
import java.util.Date
import java.util.Locale

// REJECTED rows (refused by the server) stay in the lists for history but do not count in any total or balance
private fun payAmts(list: List<SupplierPaymentE>) = list.filter { it.status != "REJECTED" }.map { PaymentAmt(it.supplierId, it.billId, it.amount, it.id, it.paidAt) }
private fun buyAmts(list: List<PurchaseE>) = list.filter { it.status != "REJECTED" }.map { PurchaseAmt(it.id, it.supplierId, it.amount, it.billDate) }

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
    val products by app.repo.products.collectAsStateWithLifecycle(emptyList())
    // brands this supplier gives; the margin of each brand is set on the supplier's info screen and is kept here
    var brandList by remember { mutableStateOf(if (s == null) emptyList() else parseSupplierBrands(s.brandsJson)) }
    val brandOptions = (products.map { it.brand.trim().uppercase() } + brandList.map { it.brand })
        .filter { it.isNotEmpty() }.distinct().sorted()
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
                Text("Brands this supplier gives", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                if (brandOptions.isEmpty()) Text("Add products first. Their brands show here.", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    brandOptions.forEach { b ->
                        val on = brandList.any { it.brand == b }
                        Chip(b, on) { brandList = if (on) brandList.filterNot { it.brand == b } else brandList + SupplierBrand(b, 0.0) }
                    }
                }
                if (s != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Active", modifier = Modifier.weight(1f)); Switch(checked = active, onCheckedChange = { active = it })
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                scope.launch {
                    try { app.repo.saveSupplier(s?.id, name, mobile, address, notes, active, photoChange, brandList); onDone() }
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
    var editing by remember { mutableStateOf(false) }
    var payOpen by remember { mutableStateOf(false) }
    var editPay by remember { mutableStateOf<SupplierPaymentE?>(null) }
    val dateFmt = remember { SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.ENGLISH) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var askDeleteBill by remember { mutableStateOf<PurchaseE?>(null) }

    val myBills = purchases.filter { it.supplierId == supplierId }.sortedByDescending { it.billDate }
    val myPays = payments.filter { it.supplierId == supplierId }.sortedByDescending { it.paidAt }
    val pa = buyAmts(myBills)
    val pay = payAmts(myPays)
    val t = SupplierMath.totals(supplierId, pa, pay)
    // where every payment went: oldest bill first (see SupplierMath.allocate)
    val alloc = SupplierMath.allocate(pa, pay)
    val billNames = myBills.associate { it.id to it.billNo.ifBlank { "Purchase" } }

    if (s == null) { EmptyState("This supplier is not on this phone yet."); return }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
        item {
            Card(Modifier.fillMaxWidth().padding(top = 8.dp), colors = CardDefaults.cardColors(containerColor = SoftGrey)) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                            Text(s.mobile.ifBlank { "No mobile" }, fontSize = 13.sp)
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
                        OutlinedButton(onClick = { payOpen = true }, modifier = Modifier.weight(1f)) { Text("Record payment") }
                    }
                }
            }
        }
        // date-wise history: every bill and every payment, with what is still owed after each line
        item {
            Text("History (${myBills.size + myPays.size})", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(top = 12.dp))
            Text("Tap a bill to see its items. Tap a payment to change it.", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val rejectedBills = myBills.filter { it.status == "REJECTED" }
        val rejectedPays = myPays.filter { it.status == "REJECTED" }
        val ledger = SupplierMath.ledger(pa, pay).reversed()
        val payById = myPays.associateBy { it.id }
        val billById = myBills.associateBy { it.id }
        if (ledger.isEmpty() && rejectedBills.isEmpty() && rejectedPays.isEmpty()) item { EmptyState("No history yet.") }
        items(rejectedBills, key = { "rejb-" + it.id }) { b ->
            Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                Text("Bill ${b.billNo.ifBlank { "Purchase" }} · ${moneyG(app, b.amount)}", fontWeight = FontWeight.SemiBold, color = RedOut)
                Text(dateFmt.format(Date(b.billDate)) + " · REJECTED by server, not counted: ${b.rejectReason}",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
        }
        items(rejectedPays, key = { "rej-" + it.id }) { p ->
            Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                Text("Payment ${moneyG(app, p.amount)} · ${p.method}", fontWeight = FontWeight.SemiBold, color = RedOut)
                Text(dateFmt.format(Date(p.paidAt)) + " · REJECTED by server, not counted: ${p.rejectReason}",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
        }
        items(ledger, key = { (if (it.isBill) "b" else "p") + it.refId }) { line ->
            if (line.isBill) {
                val b = billById[line.refId]
                if (b != null) BillLedgerRow(app, nav, supplierId, b, line.amount, line.balanceAfter, dateFmt) { askDeleteBill = it }
            } else {
                val p = payById[line.refId]
                val cheque = p?.chequeNo.orEmpty()
                val split = alloc.splits[line.refId].orEmpty()
                    .joinToString(" · ") { (bid, amt) -> "${billNames[bid] ?: "Bill"} ${moneyG(app, amt)}" }
                val goods = if (p == null) emptyList() else parsePurchaseLines(p.itemsJson)
                Row(Modifier.fillMaxWidth().clickable(enabled = p != null) { editPay = p }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Payment · " + (p?.method ?: "") + (if (cheque.isNotBlank()) " · Cheque no. $cheque" else ""),
                            fontWeight = FontWeight.SemiBold)
                        Text(dateFmt.format(Date(line.time)) + " · " + (if (split.isNotBlank()) split else "on account"),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (p != null && p.note.isNotBlank()) Text("Reason: " + p.note, fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (goods.isNotEmpty()) Text("Goods sent back: " + goods.joinToString(", ") {
                            "${it.article.ifBlank { it.name }} ${it.colour} / ${it.size} × ${it.qty}" },
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("- ${moneyG(app, line.amount)}", fontWeight = FontWeight.Bold, color = GreenOk)
                        Text("Owed ${moneyG(app, line.balanceAfter)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            HorizontalDivider()
        }
    }

    if (editing) SupplierDialog(app, s) { editing = false }
    if (payOpen) PaymentDialog(app, supplierId, t.outstanding, null) { payOpen = false }
    askDeleteBill?.let { b ->
        AlertDialog(
            onDismissRequest = { askDeleteBill = null },
            title = { Text("Delete this purchase bill?") },
            text = { Text("The bill is removed from every total and history (kept on the server, not counted or shown)." +
                (if (b.addStock) " Its items go back out of stock." else "")) },
            confirmButton = {
                TextButton(onClick = {
                    askDeleteBill = null
                    scope.launch {
                        try {
                            app.repo.changePurchase(b, b.billNo, b.billDate, emptyList(), b.amount, b.note, delete = true)
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(ctx, e.message ?: "Could not delete the bill", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text("Delete", color = RedOut) }
            },
            dismissButton = { TextButton(onClick = { askDeleteBill = null }) { Text("Keep") } }
        )
    }
    // when changing a payment, the money it already took counts as still owed for this change
    editPay?.let { p -> PaymentDialog(app, supplierId, t.outstanding + p.amount, p) { editPay = null } }
}

/** One purchase bill in the history: tap to see its items, note and bill photo, then change or delete it. */
@Composable
private fun BillLedgerRow(app: App, nav: Nav, supplierId: String, b: PurchaseE, amount: Double, balanceAfter: Double,
                          dateFmt: SimpleDateFormat, onDelete: (PurchaseE) -> Unit) {
    val ctx = LocalContext.current
    var open by remember(b.id) { mutableStateOf(false) }
    val lines = parsePurchaseLines(b.itemsJson)
    Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Bill ${b.billNo.ifBlank { "Purchase" }}", fontWeight = FontWeight.SemiBold)
                Text(dateFmt.format(Date(b.billDate)) + " · purchase" + (if (b.addStock) " · stock added" else " · money only"),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("+ ${moneyG(app, amount)}", fontWeight = FontWeight.Bold, color = RedOut)
                Text("Owed ${moneyG(app, balanceAfter)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { nav.go(Route.PurchaseForm(supplierId, b.id)) }) { Text("Edit bill") }
                TextButton(onClick = { onDelete(b) }) { Text("Delete bill", color = RedOut) }
            }
        }
    }
}

// ================================================================== SUPPLIER INFO (About)
@Composable
fun SupplierAboutScreen(app: App, nav: Nav, supplierId: String) {
    val suppliers by app.repo.suppliers.collectAsStateWithLifecycle(emptyList())
    val s = suppliers.firstOrNull { it.id == supplierId }
    var editing by remember { mutableStateOf(false) }
    var marginFor by remember { mutableStateOf<SupplierBrand?>(null) }
    if (s == null) { EmptyState("This supplier is not on this phone yet."); return }
    val brands = parseSupplierBrands(s.brandsJson)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SoftGrey)) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text(s.mobile.ifBlank { "No mobile" }, fontSize = 13.sp)
                        if (s.address.isNotBlank()) Text(s.address, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (s.notes.isNotBlank()) Text(s.notes, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (s.active) "Active" else "INACTIVE", fontSize = 12.sp,
                            color = if (s.active) GreenOk else RedOut, modifier = Modifier.padding(top = 4.dp))
                    }
                    IconButton(onClick = { editing = true }) { Icon(Icons.Filled.Edit, "Edit supplier") }
                }
            }
        }
        SectionTitle("Brands and margin")
        Text("Items of these brands are bought at MRP minus the margin. Tap a brand to change its margin.", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (brands.isEmpty()) Text("No brands yet. Tap the edit icon to tick the brands this supplier gives.", fontSize = 13.sp)
        brands.forEach { b ->
            Card(Modifier.fillMaxWidth().clickable { marginFor = b }, colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(b.brand, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("${BillMath.plain(b.margin)}% margin", fontWeight = FontWeight.Bold, color = BrandRed)
                }
            }
        }
    }
    if (editing) SupplierDialog(app, s) { editing = false }
    marginFor?.let { b -> MarginDialog(app, supplierId, brands, b) { marginFor = null } }
}

@Composable
private fun MarginDialog(app: App, supplierId: String, brands: List<SupplierBrand>, b: SupplierBrand, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(BillMath.plain(b.margin)) }
    val value = BillMath.parseAmount(text)
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Margin for ${b.brand}") },
        text = {
            OutlinedTextField(text, { text = it.filter { c -> c.isDigit() || c == '.' }.take(5) },
                label = { Text("Margin %") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        },
        confirmButton = {
            TextButton(enabled = value != null && value >= 0.0 && value < 100.0, onClick = {
                val updated = brands.map { if (it.brand == b.brand) it.copy(margin = value ?: 0.0) else it }
                scope.launch { app.repo.setSupplierBrands(supplierId, updated); onDone() }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } }
    )
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

/**
 * Records, changes or deletes money paid to a supplier. It is on account: the app spreads it over the oldest bills still owed.
 * "Return" and "Claim" record goods sent back: the amount is credited, and the items chosen here go out of stock.
 * [existing] = the payment being changed (null = a new payment).
 */
@Composable
private fun PaymentDialog(app: App, supplierId: String, outstanding: Double, existing: SupplierPaymentE?, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val dateFmt = remember { SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH) }
    val products by app.repo.products.collectAsStateWithLifecycle(emptyList())
    var paidAt by remember { mutableLongStateOf(existing?.paidAt ?: System.currentTimeMillis()) }
    var amountText by remember { mutableStateOf(if (existing == null) "" else BillMath.plain(existing.amount)) }
    var method by remember { mutableStateOf(existing?.method?.ifBlank { "Cash" } ?: "Cash") }
    var chequeNo by remember { mutableStateOf(existing?.chequeNo.orEmpty()) }
    var note by remember { mutableStateOf(existing?.note.orEmpty()) }
    val lines = remember { mutableStateListOf<PurchaseLine>().also { it.addAll(parsePurchaseLines(existing?.itemsJson.orEmpty())) } }
    var pickProduct by remember { mutableStateOf(false) }
    var pickedProduct by remember { mutableStateOf<Product?>(null) }
    var askDelete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val limit = outstanding.coerceAtLeast(0.0)
    val amount = BillMath.parseAmount(amountText)
    val isGoods = method == "Return" || method == "Claim"
    val hadGoods = parsePurchaseLines(existing?.itemsJson.orEmpty()).isNotEmpty()

    fun pickDate() {
        val c = Calendar.getInstance().apply { timeInMillis = paidAt }
        android.app.DatePickerDialog(ctx, { _, y, mo, d ->
            paidAt = Calendar.getInstance().apply { timeInMillis = paidAt; set(y, mo, d) }.timeInMillis
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    fun save() {
        if (saving) return // a second tap must not record a second payment
        saving = true; error = null
        scope.launch {
            try {
                app.repo.saveSupplierPayment(
                    existing = existing, supplierId = supplierId, amount = amount ?: 0.0, method = method,
                    chequeNo = if (method == "Cheque") chequeNo else "", note = note, paidAt = paidAt,
                    lines = if (isGoods) lines.toList() else emptyList()
                )
                onDone()
            } catch (e: Exception) { error = e.message ?: "Could not save"; saving = false }
        }
    }

    fun deletePayment() {
        val p = existing ?: return
        if (saving) return
        saving = true; error = null
        scope.launch {
            try {
                app.repo.saveSupplierPayment(existing = p, supplierId = supplierId, amount = p.amount, method = p.method,
                    chequeNo = p.chequeNo, note = p.note, paidAt = p.paidAt, lines = emptyList(), delete = true)
                onDone()
            } catch (e: Exception) { error = e.message ?: "Could not delete"; saving = false }
        }
    }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(if (existing == null) "Record payment" else "Edit payment") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Total owed ${moneyG(app, limit)}. The money goes to the oldest bill first.", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { pickDate() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Date: " + dateFmt.format(Date(paidAt)))
                }
                OutlinedTextField(
                    amountText, { amountText = it.filter { c -> c.isDigit() || c == '.' }.take(10) },
                    label = { Text((if (isGoods) "Amount credited" else "Amount paid") + " (max ${moneyG(app, limit)})") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = amountText.isNotBlank() && (amount == null || amount <= 0.0 || amount > limit + 0.005)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    listOf("Cash", "Cheque", "Online", "Return", "Claim").forEach { m -> Chip(m, method == m) { method = m } }
                }
                if (method == "Cheque") {
                    OutlinedTextField(
                        chequeNo, { chequeNo = it.take(30) }, label = { Text("Cheque number") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(), isError = chequeNo.isBlank()
                    )
                }
                if (isGoods) {
                    OutlinedTextField(note, { note = it.take(200) }, label = { Text("Reason (damaged, wrong size, ...)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("Goods sent back (optional). Stock goes down by these quantities.", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    lines.forEachIndexed { i, l ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${l.article.ifBlank { l.name }} · ${l.colour} / ${l.size} × ${l.qty}", fontSize = 13.sp,
                                modifier = Modifier.weight(1f))
                            IconButton(onClick = { lines.removeAt(i) }) { Icon(Icons.Filled.Delete, "Remove") }
                        }
                    }
                    OutlinedButton(onClick = { pickProduct = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Add item sent back")
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving && amount != null && amount > 0.0 && amount <= limit + 0.005 &&
                    (method != "Cheque" || chequeNo.isNotBlank()),
                onClick = { save() }
            ) { Text(if (saving) "Saving..." else "Save payment") }
        },
        dismissButton = {
            Row {
                if (existing != null && existing.status != "REJECTED") {
                    TextButton(enabled = !saving, onClick = { askDelete = true }) { Text("Delete", color = RedOut) }
                }
                TextButton(onClick = onDone) { Text("Cancel") }
            }
        }
    )

    if (askDelete) {
        val goodsNote = if (hadGoods) " The goods sent back go back into stock." else ""
        AlertDialog(
            onDismissRequest = { askDelete = false },
            title = { Text("Delete this payment?") },
            text = { Text("The amount goes back into the balance due.$goodsNote The row is kept on the server, but it is not counted or shown.") },
            confirmButton = { TextButton(onClick = { askDelete = false; deletePayment() }) { Text("Delete", color = RedOut) } },
            dismissButton = { TextButton(onClick = { askDelete = false }) { Text("Keep") } }
        )
    }
    if (pickProduct) ProductSearchDialog(app, products, "Choose product", { pickProduct = false }) { p ->
        pickProduct = false; pickedProduct = p
    }
    pickedProduct?.let { p ->
        PurchaseLinePicker(app, p, onDismiss = { pickedProduct = null }, withCost = false) { line ->
            lines.add(line); pickedProduct = null
        }
    }
}

// ================================================================== NEW PURCHASE BILL
@Composable
fun PurchaseFormScreen(app: App, nav: Nav, supplierId: String, billId: String? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val suppliers by app.repo.suppliers.collectAsStateWithLifecycle(emptyList())
    val products by app.repo.products.collectAsStateWithLifecycle(emptyList())
    val purchases by app.repo.purchases.collectAsStateWithLifecycle(emptyList())
    val s = suppliers.firstOrNull { it.id == supplierId }
    // changing a saved bill: the form starts with its values, and the bill number stays the same
    val editing = billId != null
    val existing = if (billId == null) null else purchases.firstOrNull { it.id == billId }
    var billNo by remember(existing?.id) { mutableStateOf(existing?.billNo.orEmpty()) }
    var dateText by remember(existing?.id) {
        mutableStateOf(SimpleDateFormat("dd-MM-yyyy", Locale.US).format(Date(existing?.billDate ?: System.currentTimeMillis())))
    }
    var addStock by remember(existing?.id) { mutableStateOf(existing?.addStock ?: true) }
    var note by remember(existing?.id) { mutableStateOf(existing?.note.orEmpty()) }
    var amountOnly by remember(existing?.id) { mutableStateOf(existing?.let { BillMath.plain(it.amount) }.orEmpty()) }
    var paidText by remember { mutableStateOf("") }
    var method by remember { mutableStateOf("Cash") }
    val lines = remember(existing?.id) {
        mutableStateListOf<PurchaseLine>().also { it.addAll(parsePurchaseLines(existing?.itemsJson.orEmpty())) }
    }
    var pickProduct by remember { mutableStateOf(false) }
    var pickedProduct by remember { mutableStateOf<Product?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selBrand by remember { mutableStateOf<String?>(null) }
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
    if (editing && existing == null) { EmptyState("Loading the bill..."); return }
    val brands = parseSupplierBrands(s.brandsJson)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (editing) "Change purchase bill from ${s.name}" else "Purchase from ${s.name}", fontWeight = FontWeight.Bold, fontSize = 18.sp)
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
            Switch(checked = addStock, onCheckedChange = { addStock = it }, enabled = !editing)
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
        if (brands.isEmpty()) {
            OutlinedButton(onClick = { pickProduct = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Add item")
            }
            Text("No brands set for this supplier yet. Edit the supplier and tick its brands, then the items of those brands show here.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val brandNow = brands.firstOrNull { it.brand == selBrand }?.brand ?: brands.first().brand
            val margin = brands.first { it.brand == brandNow }.margin
            Text("Brand", fontWeight = FontWeight.SemiBold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                brands.forEach { b -> Chip(b.brand, b.brand == brandNow) { selBrand = b.brand } }
            }
            val brandItems = products.filter { it.brand.trim().equals(brandNow, ignoreCase = true) }.sortedBy { it.article }
            if (brandItems.isEmpty()) Text("No items of $brandNow in the shop yet.", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            brandItems.forEach { p ->
                val inBill = lines.filter { it.productId == p.id }.sumOf { it.qty }
                Row(Modifier.fillMaxWidth().clickable { pickedProduct = p }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.article.ifBlank { p.name }, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text("MRP ${moneyG(app, p.mrp)} · rate ${moneyG(app, purchaseRateFor(p.mrp, margin))}" +
                            (if (inBill > 0) " · in bill: $inBill pcs" else ""), fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Filled.Add, "Add")
                }
                HorizontalDivider()
            }
        }

        if (lines.isEmpty()) OutlinedTextField(amountOnly, { amountOnly = it.filter { c -> c.isDigit() || c == '.' }.take(10) },
            label = { Text("Bill amount") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))

        OutlinedTextField(note, { note = it.take(200) }, label = { Text("Note (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())

        if (!editing) Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SoftGrey)) {
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
                if (!editing) {
                    OutlinedTextField(paidText, { paidText = it.filter { c -> c.isDigit() || c == '.' }.take(10) },
                        label = { Text("Paid now (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Cash", "UPI", "Bank", "Cheque").forEach { m -> Chip(m, method == m) { method = m } }
                    }
                    Row { Text("Balance after this bill", modifier = Modifier.weight(1f)); Text(moneyG(app, BillMath.balance(total, paid)), fontWeight = FontWeight.Bold) }
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
        Button(
            enabled = !busy && total > 0.0 && date != null && (!editing || existing != null) &&
                (editing || paidText.isBlank() || paid <= total + 0.005),
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = BrandRed),
            onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        if (existing != null) {
                            app.repo.changePurchase(existing, billNo, date!!, lines.toList(), typedAmount ?: 0.0, note)
                        } else {
                            app.repo.createPurchase(
                                supplierId = supplierId, billNo = billNo, billDate = date!!, lines = lines.toList(),
                                amountIfNoItems = typedAmount ?: 0.0, addStock = addStock, note = note,
                                paidNow = paid, method = method, billFile = billPath
                            )
                        }
                        nav.back()
                    } catch (e: Exception) {
                        error = e.message ?: "Could not save the purchase bill"
                    } finally { busy = false }
                }
            }
        ) { Text(if (busy) "Saving..." else if (editing) "Save changes" else "Save purchase bill", fontWeight = FontWeight.Bold) }
    }

    if (pickProduct) ProductSearchDialog(app, products, "Choose product", { pickProduct = false }) { p ->
        pickProduct = false; pickedProduct = p
    }
    pickedProduct?.let { p ->
        val m = brands.firstOrNull { it.brand.equals(p.brand.trim(), ignoreCase = true) }?.margin
        PurchaseLinePicker(app, p, onDismiss = { pickedProduct = null }, defaultCost = m?.let { purchaseRateFor(p.mrp, it) }) { line ->
            lines.add(line); pickedProduct = null
        }
    }
}

/**
 * Colour, size, quantity and cost for one line of a purchase bill. Every size is offered (stock can be 0).
 * withCost = false (goods sent back to the supplier): colour, size and quantity only.
 */
@Composable
private fun PurchaseLinePicker(app: App, p: Product, onDismiss: () -> Unit, withCost: Boolean = true, defaultCost: Double? = null, onAdd: (PurchaseLine) -> Unit) {
    var colour by remember(p.id) { mutableStateOf(p.variants.firstOrNull()?.colour ?: "") }
    var size by remember(p.id) { mutableStateOf<String?>(null) }
    var qty by remember(p.id) { mutableIntStateOf(1) }
    var costText by remember(p.id) { mutableStateOf(defaultCost?.let { BillMath.plain(it) } ?: "") }
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
                if (withCost) OutlinedTextField(costText, { costText = it.filter { c -> c.isDigit() || c == '.' }.take(10) },
                    label = { Text("Cost per pair") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
        },
        confirmButton = {
            TextButton(enabled = size != null && (!withCost || (cost != null && cost > 0.0)), onClick = {
                onAdd(PurchaseLine(p.id, p.article, p.name, colour, size!!, qty, if (withCost) cost!! else 0.0))
            }) { Text(if (withCost) "Add to bill" else "Add item") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
