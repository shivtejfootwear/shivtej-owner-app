@file:OptIn(ExperimentalMaterial3Api::class)

package com.shivtej.owner.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.shivtej.owner.App
import com.shivtej.owner.data.BillE
import com.shivtej.owner.data.CustomerPaymentE
import com.shivtej.owner.data.ledgerOrders
import com.shivtej.owner.data.ledgerReturns
import com.shivtej.owner.util.BillMath
import com.shivtej.owner.util.Images
import com.shivtej.owner.util.Ledger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Round photo with "Add / Replace / Remove". [onChange] gets a local file path, or "" to remove the photo. */
@Composable
fun PhotoField(title: String, localPath: String, remoteUrl: String, onChange: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                val path = withContext(Dispatchers.IO) { Images.importUri(ctx, uri) }
                busy = false
                if (path == null) toast(ctx, "This picture can not be read. Choose a JPG or PNG.") else onChange(path)
            }
        }
    }
    val model: Any? = when {
        localPath.isNotBlank() && File(localPath).exists() -> File(localPath)
        remoteUrl.isNotBlank() -> remoteUrl
        else -> null
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(76.dp).clip(CircleShape).background(SoftGrey), contentAlignment = Alignment.Center) {
            if (model != null) AsyncImage(model = model, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Icon(Icons.Filled.AddAPhoto, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = { picker.launch(arrayOf("image/*")) }, enabled = !busy) {
                Text(if (busy) "Reading..." else if (model == null) "Add photo" else "Replace photo")
            }
            if (model != null) TextButton(onClick = { onChange("") }) { Text("Remove photo", color = RedOut) }
        }
    }
}

/** Record a full or partial payment from a customer. Amounts above what is due (or left on the chosen bill) are refused. */
@Composable
fun RecordPaymentDialog(app: App, mobile: String, presetBill: String, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val bills by app.repo.bills.collectAsStateWithLifecycle(emptyList())
    val pays by app.repo.customerPayments.collectAsStateWithLifecycle(emptyList())
    val orderRows by app.repo.orders.collectAsStateWithLifecycle(emptyList())
    val returnRows by app.repo.returns.collectAsStateWithLifecycle(emptyList())
    val webOrders = remember(orderRows) { ledgerOrders(orderRows) }
    val rets = remember(returnRows) { ledgerReturns(returnRows) }
    val m = BillMath.normMobile(mobile)
    val sum = remember(bills, pays, webOrders, rets, m) { Ledger.summaryOf(m, bills, pays, webOrders, rets) }
    val openBills = remember(bills, pays, rets, m) {
        bills.filter { BillMath.normMobile(it.customerMobile) == m && Ledger.billRemaining(it, pays, rets) > 0.0 }
            .sortedBy { it.createdAt }
    }
    var billId by remember { mutableStateOf(presetBill) }
    var amount by remember { mutableStateOf("") }
    var method by remember { mutableStateOf("Cash") }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val value = BillMath.parseAmount(amount) ?: 0.0
    val chosen = openBills.firstOrNull { it.id == billId }
    val limit = if (chosen != null) Ledger.billRemaining(chosen, pays, rets) else sum.outstanding

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Record payment") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("Total due now: ${moneyG(app, sum.outstanding)}", fontWeight = FontWeight.Bold, color = RedOut)
                Text("Pay against", fontWeight = FontWeight.SemiBold)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("On account", billId.isBlank()) { billId = "" }
                    openBills.take(12).forEach { b ->
                        val left = Ledger.billRemaining(b, pays, rets)
                        Chip((b.billNo.ifBlank { "Bill" }) + " · left " + moneyG(app, left), billId == b.id) { billId = b.id }
                    }
                }
                OutlinedTextField(amount, { amount = it.filter { c -> c.isDigit() || c == '.' }.take(10) },
                    label = { Text("Amount received (max ${moneyG(app, limit)})") }, singleLine = true,
                    isError = value > limit + 0.005, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Cash", "UPI", "Bank", "Cheque").forEach { mt -> Chip(mt, method == mt) { method = mt } }
                }
                OutlinedTextField(note, { note = it.take(120) }, label = { Text("Note (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && value > 0.0 && value <= limit + 0.005, onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        app.repo.recordCustomerPayment(m, billId, value, method, note)
                        toast(ctx, "Payment saved")
                        onDone()
                    } catch (e: Exception) {
                        error = e.message ?: "Could not save the payment"
                    } finally { busy = false }
                }
            }) { Text("Save payment") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } }
    )
}

/** The account ledger of one customer: bills that owed money, payments received, and the running balance. */
fun LazyListScope.ledgerItems(
    app: App, mobile: String, bills: List<BillE>, pays: List<CustomerPaymentE>,
    orders: List<Ledger.WebOrder>, returns: List<Ledger.LedgerReturn>, nav: Nav
) {
    val fmt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.ENGLISH)
    val rows = Ledger.entries(mobile, bills, pays, orders, returns)
    item { SectionTitle("Account ledger (bills, orders, returns, payments, balance)") }
    if (rows.isEmpty()) item { EmptyState("No bills, orders or payments for this customer yet.") }
    items(rows.reversed()) { e ->
        // only shop bills open the receipt; the other rows are records that are not printed
        Row(Modifier.fillMaxWidth().clickable(enabled = e.kind == "BILL") { nav.go(Route.Receipt(e.refId)) }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(e.title, fontWeight = FontWeight.SemiBold)
                Text(fmt.format(Date(e.at)), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                if (e.credit > 0.0) {
                    val label = if (e.isPayment || e.kind == "DIFF_RECEIVED") "Received " else "Credit "
                    Text(label + moneyG(app, e.credit), fontWeight = FontWeight.Bold, color = GreenOk)
                } else if (e.debit > 0.0) {
                    val label = if (e.kind == "REFUND") "Cash refund " else "Owed "
                    Text(label + moneyG(app, e.debit), fontWeight = FontWeight.Bold, color = RedOut)
                } else Text("Paid in full", fontWeight = FontWeight.Bold, color = GreenOk)
                Text("Balance " + moneyG(app, e.balance), fontSize = 12.sp, color = if (e.balance > 0.0) RedOut else GreenOk)
            }
        }
        HorizontalDivider()
    }
}
