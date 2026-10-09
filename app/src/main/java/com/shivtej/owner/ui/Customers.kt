@file:OptIn(ExperimentalMaterial3Api::class)

package com.shivtej.owner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.shivtej.owner.App
import com.shivtej.owner.data.*
import com.shivtej.owner.util.BillMath
import com.shivtej.owner.util.Ledger
import kotlinx.coroutines.launch
import java.io.File
import java.util.Date
import java.util.Locale

/** One customer as the Customers screen shows them: saved details + everything they bought (shop bills and website orders). */
data class CustRow(
    val mobile: String,
    val name: String,
    val address: String,
    val email: String,
    val bills: Int,
    val spent: Double,
    val lastAt: Long,
    val outstanding: Double = 0.0,      // udhaar still to be paid (shop bills minus payments received)
    val photoPath: String = "",
    val photoUrl: String = ""
)

/** Every customer the phone knows about, merged by mobile number so the same customer never appears twice. */
fun buildCustRows(
    posC: List<PosCustomerE>, webC: List<CustomerE>, bills: List<BillE>, orders: List<OrderE>,
    pays: List<CustomerPaymentE> = emptyList(), returns: List<ReturnE> = emptyList()
): List<CustRow> {
    class Acc(var mobile: String) {
        var name = ""; var address = ""; var email = ""; var count = 0; var spent = 0.0; var last = 0L
        var photoPath = ""; var photoUrl = ""
    }
    val map = LinkedHashMap<String, Acc>()
    fun acc(raw: String): Acc? {
        val m = BillMath.normMobile(raw)
        if (m.length != 10) return null
        return map.getOrPut(m) { Acc(m) }
    }
    for (c in posC) acc(c.mobile)?.let { it.name = c.name; it.address = c.address; it.photoPath = c.photoPath; it.photoUrl = c.photoUrl }
    for (c in webC) acc(c.mobile)?.let { if (it.name.isBlank()) it.name = c.name; if (it.email.isBlank()) it.email = c.email }
    for (b in bills) acc(b.customerMobile)?.let {
        it.count++; it.spent += b.total; it.last = maxOf(it.last, b.createdAt)
        if (it.name.isBlank()) it.name = b.customerName
        if (it.address.isBlank()) it.address = b.customerAddress
    }
    for (o in orders) {
        if (o.orderStatus == "Cancelled") continue
        acc(custField(o.customerJson, "mobile", "phone"))?.let {
            it.count++; it.spent += o.total; it.last = maxOf(it.last, o.createdAt)
            if (it.name.isBlank()) it.name = custField(o.customerJson, "name")
        }
    }
    val sums = Ledger.summaries(bills, pays, ledgerOrders(orders), ledgerReturns(returns))
    return map.values.map {
        CustRow(it.mobile, it.name, it.address, it.email, it.count, BillMath.round2(it.spent), it.last,
            outstanding = sums[it.mobile]?.outstanding ?: 0.0, photoPath = it.photoPath, photoUrl = it.photoUrl)
    }
        .sortedWith(compareByDescending<CustRow> { it.spent }.thenBy { it.name.lowercase() })
}

/** Round avatar: the customer's photo when there is one, otherwise the first letter of the name. */
@Composable
fun PersonAvatar(name: String, localPath: String, remoteUrl: String, size: Dp = 40.dp) {
    val model: Any? = when {
        localPath.isNotBlank() && File(localPath).exists() -> File(localPath)
        remoteUrl.isNotBlank() -> remoteUrl
        else -> null
    }
    Box(Modifier.size(size).background(SoftRed, CircleShape), contentAlignment = Alignment.Center) {
        if (model != null) AsyncImage(model = model, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().clip(CircleShape))
        else Text((name.ifBlank { "C" }).take(1).uppercase(), color = BrandRed, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun CustomersScreen(app: App, nav: Nav, openAdd: Boolean = false) {
    val posC by app.repo.posCustomers.collectAsStateWithLifecycle(emptyList())
    val webC by app.repo.customers.collectAsStateWithLifecycle(emptyList())
    val bills by app.repo.bills.collectAsStateWithLifecycle(emptyList())
    val orders by app.repo.orders.collectAsStateWithLifecycle(emptyList())
    val pays by app.repo.customerPayments.collectAsStateWithLifecycle(emptyList())
    val returns by app.repo.returns.collectAsStateWithLifecycle(emptyList())
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("all") }      // all | owes | clear
    var adding by remember { mutableStateOf(openAdd) }
    val rows = remember(posC, webC, bills, orders, pays, returns) { buildCustRows(posC, webC, bills, orders, pays, returns) }

    val q = query.trim()
    val digits = q.filter { it.isDigit() }
    val byStatus = rows.filter { r ->
        when (status) { "owes" -> r.outstanding > 0.0; "clear" -> r.outstanding <= 0.0; else -> true }
    }
    val shown = byStatus.filter { r ->
        q.isEmpty() ||
            (digits.isNotEmpty() && q.all { it.isDigit() || it == ' ' || it == '+' } && r.mobile.contains(digits)) ||
            r.name.contains(q, true) || r.address.contains(q, true) || r.email.contains(q, true)
    }
    // typing a full 10-digit mobile number brings that customer to the top
    val exact = if (digits.length == 10) rows.firstOrNull { it.mobile == digits } else null
    val owing = rows.filter { it.outstanding > 0.0 }
    val totalDue = BillMath.round2(owing.sumOf { it.outstanding })

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                placeholder = { Text("Search by mobile number, name or address") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )
            Card(Modifier.fillMaxWidth().padding(top = 8.dp).clickable { status = "owes" },
                colors = CardDefaults.cardColors(containerColor = if (totalDue > 0.0) SoftRed else SoftGrey)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Total outstanding (udhaar)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(moneyG(app, totalDue), fontWeight = FontWeight.Bold, fontSize = 20.sp, color = if (totalDue > 0.0) RedOut else GreenOk)
                    }
                    Text("${owing.size} customer(s) owe money", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                Chip("All", status == "all") { status = "all" }
                Chip("Owes money", status == "owes") { status = "owes" }
                Chip("No dues", status == "clear") { status = "clear" }
            }
            Text("${shown.size} customer(s) · ${rows.size} saved in total", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
            if (exact != null) {
                Card(Modifier.fillMaxWidth().clickable { nav.go(Route.CustomerDetail(exact.mobile)) }.padding(bottom = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = SoftRed)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Found: " + exact.name.ifBlank { "Customer" }, fontWeight = FontWeight.Bold)
                            Text(exact.mobile + " · " + exact.bills + " purchase(s)", fontSize = 12.sp)
                        }
                        Text("Open", color = BrandRed, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (shown.isEmpty()) EmptyState(
                if (rows.isEmpty()) "No customers yet. Tap + to add one, or they are saved automatically when you bill with a mobile number."
                else if (status == "owes") "No customer owes money right now."
                else "No customer matches."
            )
            LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp)) {
                items(shown, key = { it.mobile }) { c ->
                    Row(Modifier.fillMaxWidth().clickable { nav.go(Route.CustomerDetail(c.mobile)) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        PersonAvatar(c.name, c.photoPath, c.photoUrl, 40.dp)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(c.name.ifBlank { "Customer" }, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(c.mobile + if (c.address.isNotBlank()) " · " + c.address else "", fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(moneyG(app, c.spent), fontWeight = FontWeight.Bold)
                            if (c.outstanding > 0.0) Text("Due " + moneyG(app, c.outstanding), fontSize = 12.sp,
                                color = RedOut, fontWeight = FontWeight.Bold)
                            else Text("${c.bills} purchase(s)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
        FloatingActionButton(
            onClick = { adding = true }, containerColor = BrandRed, contentColor = Color.White,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) { Icon(Icons.Filled.PersonAdd, "Add customer") }
    }
    if (adding) CustomerDialog(app, existing = null, takenMobiles = rows.map { it.mobile }.toSet(), onOpen = { nav.go(Route.CustomerDetail(it)) }) { adding = false }
}

/**
 * Add or edit a customer. A mobile number can be saved only once: if it already exists the owner is offered to open it.
 * The photo is optional: picking one saves it, Remove deletes it (on the server too, when the phone is online).
 */
@Composable
fun CustomerDialog(
    app: App, existing: CustRow?, takenMobiles: Set<String>, onOpen: (String) -> Unit, onDone: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var mobile by remember { mutableStateOf(existing?.mobile ?: "") }
    var address by remember { mutableStateOf(existing?.address ?: "") }
    // null = photo unchanged, "" = photo removed, otherwise the path of the new picture
    var photoChange by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val m = BillMath.normMobile(mobile)
    val clash = existing == null && m.length == 10 && m in takenMobiles
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(if (existing == null) "Add customer" else "Edit customer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                PhotoField("Customer photo (optional)",
                    localPath = (photoChange ?: existing?.photoPath).orEmpty(),
                    remoteUrl = if (photoChange == "") "" else existing?.photoUrl.orEmpty()) { photoChange = it }
                OutlinedTextField(mobile, { if (existing == null) mobile = it.filter(Char::isDigit).take(10) }, label = { Text("Mobile number") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), readOnly = existing != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    isError = mobile.isNotEmpty() && m.length != 10,
                    supportingText = { if (mobile.isNotEmpty() && m.length != 10) Text("Must be 10 digits") })
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(address, { address = it }, label = { Text("City / Address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (clash) Text("This mobile number is already saved. Open that customer to edit it.", color = AmberLow, fontSize = 13.sp)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            if (clash) TextButton(onClick = { onDone(); onOpen(m) }) { Text("Open customer") }
            else TextButton(enabled = m.length == 10 && name.isNotBlank(), onClick = {
                scope.launch {
                    try {
                        app.repo.saveCustomer(m, name, address, photoChange)
                        onDone()
                    } catch (e: Exception) { error = e.message ?: "Could not save" }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } }
    )
}

/** One purchase in a customer's history: a shop bill or a website order. */
private data class PurchaseRow(
    val id: String, val isOrder: Boolean, val at: Long, val label: String, val amount: Double,
    val paid: String, val balance: Double, val items: String
)

@Composable
fun CustomerDetailScreen(app: App, nav: Nav, mobile: String) {
    val posC by app.repo.posCustomers.collectAsStateWithLifecycle(emptyList())
    val webC by app.repo.customers.collectAsStateWithLifecycle(emptyList())
    val bills by app.repo.bills.collectAsStateWithLifecycle(emptyList())
    val orders by app.repo.orders.collectAsStateWithLifecycle(emptyList())
    val pays by app.repo.customerPayments.collectAsStateWithLifecycle(emptyList())
    val returns by app.repo.returns.collectAsStateWithLifecycle(emptyList())
    var editing by remember { mutableStateOf(false) }
    // null = closed; "" = payment on account; otherwise the id of the bill being paid
    var paying by remember { mutableStateOf<String?>(null) }
    val row = remember(posC, webC, bills, orders, pays, returns, mobile) {
        buildCustRows(posC, webC, bills, orders, pays, returns).firstOrNull { it.mobile == mobile }
    }
    val myBills = bills.filter { BillMath.normMobile(it.customerMobile) == mobile }
    val myOrders = orders.filter { it.orderStatus != "Cancelled" && custField(it.customerJson, "mobile", "phone").let { m -> BillMath.normMobile(m) == mobile } }
    val sum = remember(bills, pays, orders, returns, mobile) {
        Ledger.summaryOf(mobile, bills, pays, ledgerOrders(orders), ledgerReturns(returns))
    }
    val history = remember(myBills, myOrders, pays, returns) {
        val ledgerRets = ledgerReturns(returns)
        (myBills.map { b ->
            val its = parseBillItems(b.itemsJson)
            PurchaseRow(b.id, false, b.createdAt, b.billNo, b.total, b.method, Ledger.billRemaining(b, pays, ledgerRets),
                its.joinToString(", ") { "${it.article.ifBlank { it.name }} ${it.colour}/${it.size} x${it.qty}" })
        } + myOrders.map { o ->
            val its = parseBillItems(o.itemsJson)
            PurchaseRow(o.id, true, o.createdAt, "Online order", o.total, o.paymentStatus.ifBlank { "-" }, 0.0,
                its.joinToString(", ") { "${it.article.ifBlank { it.name }} ${it.colour}/${it.size} x${it.qty}" })
        }).sortedByDescending { it.at }
    }
    val dateFmt = remember { java.text.SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.ENGLISH) }
    val spent = history.sumOf { it.amount }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
        item {
            Card(Modifier.fillMaxWidth().padding(top = 8.dp), colors = CardDefaults.cardColors(containerColor = SoftGrey)) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PersonAvatar(row?.name.orEmpty(), row?.photoPath.orEmpty(), row?.photoUrl.orEmpty(), 56.dp)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(row?.name?.takeIf { it.isNotBlank() } ?: "Customer", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                            Text(mobile, fontSize = 14.sp)
                            Text(row?.address?.takeIf { it.isNotBlank() } ?: "No address saved", fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            row?.email?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        IconButton(onClick = { editing = true }) { Icon(Icons.Filled.Edit, "Edit customer") }
                    }
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile("Purchases", history.size.toString(), Modifier.weight(1f))
                        StatTile("Total spent", moneyG(app, spent), Modifier.weight(1f))
                    }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile("Last visit", row?.lastAt?.takeIf { it > 0 }?.let { dateFmt.format(Date(it)) } ?: "-", Modifier.weight(1f))
                        StatTile("Average bill", moneyG(app, if (history.isEmpty()) 0.0 else spent / history.size), Modifier.weight(1f))
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth().padding(top = 8.dp),
                colors = CardDefaults.cardColors(containerColor = if (sum.outstanding > 0.0) SoftRed else Color(0xFFE8F5E9))) {
                Column(Modifier.padding(14.dp)) {
                    Text("Outstanding (udhaar)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(moneyG(app, sum.outstanding), fontWeight = FontWeight.Bold, fontSize = 24.sp,
                        color = if (sum.outstanding > 0.0) RedOut else GreenOk)
                    Text("Billed ${moneyG(app, sum.billed)} · Received ${moneyG(app, sum.payments)}", fontSize = 12.sp)
                    if (sum.advance > 0.0) Text("Advance with the shop: ${moneyG(app, sum.advance)}", fontSize = 12.sp, color = GreenOk)
                    Button(enabled = sum.outstanding > 0.0, onClick = { paying = "" }, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Receive payment")
                    }
                }
            }
        }
        ledgerItems(app, mobile, bills, pays, ledgerOrders(orders), ledgerReturns(returns), nav)
        item { SectionTitle("Purchase history (tap a shop bill to view or print it)") }
        if (history.isEmpty()) item { EmptyState("No purchases yet for this customer.") }
        items(history, key = { it.id }) { p ->
            Column(Modifier.fillMaxWidth().clickable {
                if (p.isOrder) nav.go(Route.OrderDetail(p.id)) else nav.go(Route.Receipt(p.id))
            }.padding(vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.label, fontWeight = FontWeight.SemiBold)
                        Text(dateFmt.format(Date(p.at)) + " · " + p.paid, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(moneyG(app, p.amount), fontWeight = FontWeight.Bold)
                        if (!p.isOrder && p.balance > 0) {
                            Text("Balance ${moneyG(app, p.balance)}", fontSize = 11.sp, color = RedOut)
                            TextButton(onClick = { paying = p.id }) { Text("Receive for this bill") }
                        }
                    }
                }
                Text(p.items.ifBlank { "Items not saved" }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, modifier = Modifier.padding(top = 4.dp))
            }
            HorizontalDivider()
        }
    }

    paying?.let { preset -> RecordPaymentDialog(app, mobile, preset) { paying = null } }
    if (editing && row != null) CustomerDialog(app, existing = row, takenMobiles = emptySet(), onOpen = {}) { editing = false }
    else if (editing) { editing = false }
}

@Composable
private fun StatTile(title: String, value: String, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(10.dp)) {
            Text(title, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = BrandRed, maxLines = 1)
        }
    }
}
