@file:OptIn(ExperimentalMaterial3Api::class)

package com.shivtej.owner.ui

import android.Manifest
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
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
import androidx.compose.ui.text.input.KeyboardCapitalization
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
import java.text.SimpleDateFormat
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
    val photoUrl: String = "",
    val oldestDueAt: Long = 0L,         // when the oldest unpaid amount was made (Ledger.OPENING_AT = before the app)
    val openingBalance: Double = 0.0,   // dues from before the app, entered by the owner
    val shop: Boolean = false,          // a shop (offline) customer: saved in the shop list or has shop bills
    val online: Boolean = false,        // an online (website) customer: has a website account or website orders
    val offlineContact: String = "",    // the shop number ("" = not a shop customer)
    val onlineContact: String = ""      // the website number ("" = none); can be the same as the shop number
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
    // shop (offline) and online (website) numbers are kept apart: a number is "shop" if it is in the shop list or
    // has a shop bill, and "online" if it has a website account or a website order. Only the list is split; no data moves.
    val shopMobiles = HashSet<String>()
    val onlineMobiles = HashSet<String>()
    val posOnline = HashMap<String, String>()   // shop number -> the online contact typed for that customer
    fun acc(raw: String): Acc? {
        val m = BillMath.normMobile(raw)
        if (m.length != 10) return null
        return map.getOrPut(m) { Acc(m) }
    }
    for (c in posC) acc(c.mobile)?.let { shopMobiles.add(it.mobile); posOnline[it.mobile] = BillMath.normMobile(c.onlineContact).takeIf { x -> x.length == 10 }.orEmpty(); it.name = c.name; it.address = c.address; it.photoPath = c.photoPath; it.photoUrl = c.photoUrl }
    for (c in webC) acc(c.mobile)?.let { onlineMobiles.add(it.mobile); if (it.name.isBlank()) it.name = c.name; if (it.email.isBlank()) it.email = c.email }
    for (b in bills) acc(b.customerMobile)?.let {
        shopMobiles.add(it.mobile)
        it.count++; it.spent += b.total; it.last = maxOf(it.last, b.createdAt)
        if (it.name.isBlank()) it.name = b.customerName
        if (it.address.isBlank()) it.address = b.customerAddress
    }
    for (o in orders) {
        if (o.orderStatus == "Cancelled") continue
        acc(custField(o.customerJson, "mobile", "phone"))?.let {
            onlineMobiles.add(it.mobile)
            it.count++; it.spent += o.total; it.last = maxOf(it.last, o.createdAt)
            if (it.name.isBlank()) it.name = custField(o.customerJson, "name")
        }
    }
    val openings = openingsOf(posC)
    val sums = Ledger.summaries(bills, pays, ledgerOrders(orders), ledgerReturns(returns), openings)
    return map.values.map {
        val shopRow = it.mobile in shopMobiles
        val onlineNo = posOnline[it.mobile].orEmpty()
        // the online contact: the one typed on the customer, otherwise the number itself when it is a website customer
        val onlineShown = onlineNo.ifBlank { if (it.mobile in onlineMobiles) it.mobile else "" }
        CustRow(it.mobile, it.name, it.address, it.email, it.count, BillMath.round2(it.spent), it.last,
            outstanding = sums[it.mobile]?.outstanding ?: 0.0, photoPath = it.photoPath, photoUrl = it.photoUrl,
            oldestDueAt = sums[it.mobile]?.oldestDueAt ?: 0L, openingBalance = openings[it.mobile] ?: 0.0,
            shop = shopRow, online = it.mobile in onlineMobiles || onlineNo.isNotBlank(),
            offlineContact = if (shopRow) it.mobile else "", onlineContact = onlineShown)
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
fun CustomersScreen(app: App, nav: Nav, openAdd: Boolean = false, initialStatus: String = "all", fixedKind: String = "all") {
    val posC by app.repo.posCustomers.collectAsStateWithLifecycle(emptyList())
    val webC by app.repo.customers.collectAsStateWithLifecycle(emptyList())
    val bills by app.repo.bills.collectAsStateWithLifecycle(emptyList())
    val orders by app.repo.orders.collectAsStateWithLifecycle(emptyList())
    val pays by app.repo.customerPayments.collectAsStateWithLifecycle(emptyList())
    val returns by app.repo.returns.collectAsStateWithLifecycle(emptyList())
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(initialStatus) }      // all | owes | clear
    val kind = fixedKind                                          // all | shop (offline) | online (website): set by the menu item, no chips
    var sort by remember { mutableStateOf(if (initialStatus == "owes") "due" else "spent") }  // spent | due | oldest
    var adding by remember { mutableStateOf(openAdd) }
    val rows = remember(posC, webC, bills, orders, pays, returns) { buildCustRows(posC, webC, bills, orders, pays, returns) }

    val q = query.trim()
    val digits = q.filter { it.isDigit() }
    // Offline list: only shop customers who are NOT online (a customer who also orders online is shown only under Online)
    val scoped = rows.filter { r ->
        when (kind) { "shop" -> r.shop && !r.online; "online" -> r.online; else -> true }
    }
    val byKind = scoped
    val byStatus = byKind.filter { r ->
        when (status) { "owes" -> r.outstanding > 0.0; "clear" -> r.outstanding <= 0.0; else -> true }
    }
    val shown = byStatus.filter { r ->
        q.isEmpty() ||
            (digits.isNotEmpty() && q.all { it.isDigit() || it == ' ' || it == '+' } &&
                (r.mobile.contains(digits) || r.onlineContact.contains(digits))) ||
            r.name.contains(q, true) || r.address.contains(q, true) || r.email.contains(q, true)
    }.let { list ->
        when (sort) {
            // the highest amount still to be paid first
            "due" -> list.sortedWith(compareByDescending<CustRow> { it.outstanding }
                .thenBy { if (it.oldestDueAt == 0L) Long.MAX_VALUE else it.oldestDueAt })
            // the oldest pending amount first; customers who owe nothing last
            "oldest" -> list.sortedWith(compareBy<CustRow>({ if (it.outstanding > 0.0) 0 else 1 },
                { if (it.oldestDueAt == 0L) Long.MAX_VALUE else it.oldestDueAt }, { -it.outstanding }))
            else -> list
        }
    }
    // typing a full 10-digit mobile number brings that customer to the top
    val exact = if (digits.length == 10) scoped.firstOrNull { it.mobile == digits } else null
    val owing = scoped.filter { it.outstanding > 0.0 }
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
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                Chip("Most spent", sort == "spent") { sort = "spent" }
                Chip("Highest due", sort == "due") { sort = "due" }
                Chip("Oldest due", sort == "oldest") { sort = "oldest" }
            }
            Text("${shown.size} customer(s) · ${scoped.size} saved in total", fontSize = 12.sp,
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
                            Text(when {
                                c.shop && c.online -> "Shop + Online customer"
                                c.online -> "Online (website) customer"
                                else -> "Shop (offline) customer"
                            }, fontSize = 11.sp, color = if (c.online) BrandRed else MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Offline: " + c.offlineContact.ifBlank { "-" } +
                                (if (c.onlineContact.isNotBlank()) "  ·  Online: " + c.onlineContact else "") +
                                (if (c.address.isNotBlank()) "  ·  " + c.address else ""), fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(moneyG(app, c.spent), fontWeight = FontWeight.Bold)
                            if (c.outstanding > 0.0) Text("Due " + moneyG(app, c.outstanding), fontSize = 12.sp,
                                color = RedOut, fontWeight = FontWeight.Bold)
                            else Text("${c.bills} purchase(s)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (c.outstanding > 0.0 && c.oldestDueAt > 0L) Text("since " + sinceLabel(c.oldestDueAt), fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var mobile by remember { mutableStateOf(if (existing == null || existing.shop) existing?.mobile.orEmpty() else "") }
    var address by remember { mutableStateOf(existing?.address ?: "") }
    // a website-only customer has no shop number yet: the owner can add one (their website number stays as the online contact)
    val shopRecord = existing == null || existing.shop
    var onlineText by remember { mutableStateOf(existing?.onlineContact.orEmpty()) }
    var opening by remember { mutableStateOf(existing?.openingBalance?.takeIf { it != 0.0 }?.let { plainAmount(it) } ?: "") }

    // Pick a name and number from the phone's Contacts (asked once, only when the owner taps the button).
    val contactPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        if (uri != null) {
            val picked = readPhoneContact(ctx, uri)
            if (picked == null) toast(ctx, "This contact has no 10 digit mobile number.")
            else {
                mobile = picked.second
                if (name.isBlank()) name = picked.first
            }
        }
    }
    val contactsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) contactPicker.launch(null) else toast(ctx, "Contacts permission was not given. Type the number instead.")
    }
    // null = photo unchanged, "" = photo removed, otherwise the path of the new picture
    var photoChange by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val m = BillMath.normMobile(mobile)
    val onlineNo = BillMath.normMobile(onlineText)
    val onlineBad = onlineText.isNotBlank() && onlineNo.length != 10
    val clash = (existing == null || !shopRecord) && m.length == 10 && m in takenMobiles && m != existing?.mobile
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(if (existing == null) "Add customer" else "Edit customer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                PhotoField("Customer photo (optional)",
                    localPath = (photoChange ?: existing?.photoPath).orEmpty(),
                    remoteUrl = if (photoChange == "") "" else existing?.photoUrl.orEmpty()) { photoChange = it }
                OutlinedTextField(mobile, { if (!(shopRecord && existing != null)) mobile = it.filter(Char::isDigit).take(10) },
                    label = { Text("Offline contact (shop number)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), readOnly = existing != null && shopRecord,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    isError = mobile.isNotEmpty() && m.length != 10,
                    supportingText = { if (mobile.isNotEmpty() && m.length != 10) Text("Must be 10 digits") })
                OutlinedTextField(onlineText, { onlineText = it.filter(Char::isDigit).take(12) },
                    label = { Text("Online contact (website number, optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), isError = onlineBad,
                    supportingText = { Text(if (onlineBad) "Must be 10 digits" else "Can be the same as the shop number, or empty.", fontSize = 11.sp) })
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
                OutlinedTextField(address, { address = it }, label = { Text("City / Address") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
                if (existing == null) OutlinedButton(onClick = {
                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                        contactPicker.launch(null)
                    else contactsPermission.launch(Manifest.permission.READ_CONTACTS)
                }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Phone, null)
                    Text("  Pick from phone contacts")
                }
                OutlinedTextField(opening, { opening = it.filter { c -> c.isDigit() || c == '.' }.take(10) },
                    label = { Text("Opening balance (dues before the app)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    supportingText = { Text("Leave empty if none. Later bills and payments are counted automatically.", fontSize = 11.sp) })
                if (clash) Text("This mobile number is already saved. Open that customer to edit it.", color = AmberLow, fontSize = 13.sp)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            if (clash) TextButton(onClick = { onDone(); onOpen(m) }) { Text("Open customer") }
            else TextButton(enabled = m.length == 10 && name.isNotBlank() && !onlineBad, onClick = {
                val openingValue = if (opening.isBlank()) 0.0 else BillMath.parseAmount(opening)
                if (openingValue == null) {
                    error = "Opening balance must be a number"
                } else {
                    scope.launch {
                        try {
                            app.repo.saveCustomer(m, name, address, photoChange, openingValue, onlineText)
                            onDone()
                        } catch (e: Exception) { error = e.message ?: "Could not save" }
                    }
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
    val openings = remember(posC) { openingsOf(posC) }
    val sum = remember(bills, pays, orders, returns, mobile, openings) {
        Ledger.summaryOf(mobile, bills, pays, ledgerOrders(orders), ledgerReturns(returns), openings)
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
                            Text("Offline contact: " + (row?.offlineContact?.takeIf { it.isNotBlank() } ?: "not saved (use Edit to add the shop number)"), fontSize = 13.sp)
                            Text("Online contact: " + (row?.onlineContact?.takeIf { it.isNotBlank() } ?: "none"), fontSize = 13.sp)
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
                    val opening = openings[mobile] ?: 0.0
                    if (opening != 0.0) Text("Includes the opening balance from before the app: ${moneyG(app, opening)}", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (sum.advance > 0.0) Text("Advance with the shop: ${moneyG(app, sum.advance)}", fontSize = 12.sp, color = GreenOk)
                    Button(enabled = sum.outstanding > 0.0, onClick = { paying = "" }, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Receive payment")
                    }
                }
            }
        }
        ledgerItems(app, mobile, bills, pays, ledgerOrders(orders), ledgerReturns(returns), nav, openings)
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

/** "dd MMM yyyy" for the date of the oldest unpaid amount, or "before the app" for the opening balance. */
fun sinceLabel(at: Long): String =
    if (at == Ledger.OPENING_AT) "before the app" else SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).format(Date(at))

/** An amount without trailing zeros, for typing into a field: 500.0 -> "500", 250.5 -> "250.5". */
fun plainAmount(v: Double): String = BillMath.round2(v).let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }

/** Reads the name and the 10 digit mobile number of a contact picked from the phone's Contacts app. Null when there is none. */
fun readPhoneContact(ctx: Context, uri: Uri): Pair<String, String>? {
    val cr = ctx.contentResolver
    var contactId: String? = null
    var name = ""
    cr.query(uri, arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) { contactId = c.getString(0); name = c.getString(1).orEmpty() }
    }
    val id = contactId ?: return null
    var number: String? = null
    cr.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
        "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?", arrayOf(id), null)?.use { c ->
        while (number == null && c.moveToNext()) {
            val n = BillMath.normMobile(c.getString(0).orEmpty())
            if (n.length == 10) number = n
        }
    }
    return number?.let { name to it }
}

/** Online customers: only their numbers (the website customers), with search. No names, no dues. */
@Composable
fun OnlineCustomerNumbersScreen(app: App, nav: Nav) {
    val webC by app.repo.customers.collectAsStateWithLifecycle(emptyList())
    val bills by app.repo.bills.collectAsStateWithLifecycle(emptyList())
    val orders by app.repo.orders.collectAsStateWithLifecycle(emptyList())
    val pays by app.repo.customerPayments.collectAsStateWithLifecycle(emptyList())
    val returns by app.repo.returns.collectAsStateWithLifecycle(emptyList())
    val posC by app.repo.posCustomers.collectAsStateWithLifecycle(emptyList())
    var query by remember { mutableStateOf("") }
    val rows = remember(posC, webC, bills, orders, pays, returns) {
        buildCustRows(posC, webC, bills, orders, pays, returns).filter { it.online }
            .map { it to it.onlineContact.ifBlank { it.mobile } }
            .filter { it.second.isNotBlank() }
            .sortedBy { it.second }
    }
    val q = query.trim().filter { it.isDigit() }
    val shown = if (q.isEmpty()) rows else rows.filter { it.second.contains(q) }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            placeholder = { Text("Search by mobile number") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
        )
        Text("${shown.size} online customer number(s)", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
        if (shown.isEmpty()) EmptyState(
            if (rows.isEmpty()) "No online customers yet. They appear here when someone orders on the website."
            else "No number matches."
        )
        LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
            items(shown, key = { it.second }) { (c, number) ->
                Row(Modifier.fillMaxWidth().clickable { nav.go(Route.CustomerDetail(c.mobile)) }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(number, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Text("Open", color = BrandRed, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                HorizontalDivider()
            }
        }
    }
}
