@file:OptIn(ExperimentalMaterial3Api::class)

package com.shivtej.owner.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ------------------------------------------------------------------ order parsing
data class OItem(val name: String, val article: String, val colour: String, val size: String, val qty: Int, val price: Double)

fun parseOrderItems(json: String, products: List<Product>): List<OItem> = try {
    val a = JSONArray(json)
    (0 until a.length()).mapNotNull { i ->
        val o = a.optJSONObject(i) ?: return@mapNotNull null
        val pid = o.str("productId").ifBlank { o.str("id") }
        val p = products.firstOrNull { it.id == pid } ?: products.firstOrNull { it.article.isNotBlank() && it.article == o.str("article") }
        OItem(
            o.str("name").ifBlank { p?.name ?: "Item" },
            o.str("article").ifBlank { p?.article ?: "" },
            o.str("colour"), o.str("size"),
            if (o.has("qty")) o.optInt("qty", 1) else o.optInt("q", 1),
            if (o.has("price")) o.optDouble("price", 0.0) else (p?.price ?: 0.0)
        )
    }
} catch (e: Exception) { emptyList() }

fun custField(json: String, vararg keys: String): String = try {
    val o = JSONObject(json)
    keys.map { o.str(it) }.firstOrNull { it.isNotBlank() } ?: ""
} catch (e: Exception) { "" }

private fun statusColor(s: String) = when (s) {
    "New" -> AmberLow
    "Cancelled", "Returned" -> RedOut
    "Delivered" -> GreenOk
    else -> androidx.compose.ui.graphics.Color(0xFF1565C0)
}

// ================================================================== ORDERS
@Composable
fun OrdersScreen(app: App, nav: Nav, mode: String) {
    val orders by app.repo.orders.collectAsStateWithLifecycle(emptyList())
    var filter by remember(mode) { mutableStateOf("all") }
    var query by remember { mutableStateOf("") }
    val fmt = remember { SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()) }

    val base = when (mode) {
        "pending" -> orders.filter { it.orderStatus == "New" }
        "shipping" -> orders.filter { it.orderStatus in listOf("Confirmed", "Packed", "Shipped") }
        else -> orders
    }
    val shown = base.filter { o ->
        (filter == "all" || o.orderStatus == filter) && (query.isBlank() ||
            o.id.contains(query, true) || custField(o.customerJson, "name").contains(query, true) ||
            custField(o.customerJson, "mobile", "phone").contains(query, true))
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        SearchField(query, { query = it }, "Search order ID, customer, mobile", Modifier.padding(top = 8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("All", filter == "all") { filter = "all" }
            ALL_ORDER_STATUSES.forEach { s -> Chip(s, filter == s) { filter = s } }
        }
        if (shown.isEmpty()) EmptyState(
            if (orders.isEmpty()) "No website orders yet. They appear here after the website saves orders to the database."
            else "No orders match."
        )
        LazyColumn {
            items(shown, key = { it.id }) { o ->
                Row(Modifier.fillMaxWidth().clickable { nav.go(Route.OrderDetail(o.id)) }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("#${o.id}", fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(custField(o.customerJson, "name").ifBlank { "Customer" } + " · " +
                            custField(o.customerJson, "mobile", "phone"), fontSize = 13.sp, maxLines = 1)
                        Text(fmt.format(Date(o.createdAt)), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(money(app, o.total), fontWeight = FontWeight.Bold)
                        Text(o.orderStatus.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = statusColor(o.orderStatus))
                        if (o.pendingSync) Text("waiting to sync", fontSize = 10.sp, color = AmberLow)
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun OrderDetailScreen(app: App, nav: Nav, id: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val orders by app.repo.orders.collectAsStateWithLifecycle(emptyList())
    val products by app.repo.products.collectAsStateWithLifecycle(emptyList())
    val o = orders.firstOrNull { it.id == id }
    if (o == null) { EmptyState("Order not found."); return }

    var status by remember(o.id) { mutableStateOf(o.orderStatus) }
    var courier by remember(o.id) { mutableStateOf(o.courier) }
    var tracking by remember(o.id) { mutableStateOf(o.trackingNo) }
    var shipping by remember(o.id) { mutableStateOf(if (o.shipping == 0.0) "" else o.shipping.toString()) }
    val items = remember(o.itemsJson, products) { parseOrderItems(o.itemsJson, products) }
    val fmt = remember { SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text("Order #${o.id}", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(fmt.format(Date(o.createdAt)), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        SectionTitle("Customer")
        Text(custField(o.customerJson, "name").ifBlank { "-" })
        Text("Mobile: " + custField(o.customerJson, "mobile", "phone").ifBlank { "-" })
        val addr = custField(o.customerJson, "address", "addr")
        if (addr.isNotBlank()) Text("Address: $addr")

        SectionTitle("Items")
        if (items.isEmpty()) Text("No item details.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        items.forEach { it ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(it.name, fontWeight = FontWeight.SemiBold)
                    Text("${it.article} · ${it.colour} / Size ${it.size} · Qty ${it.qty}", fontSize = 12.sp)
                }
                Text(money(app, it.price * it.qty))
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        Row { Text("Subtotal", Modifier.weight(1f)); Text(money(app, o.subtotal)) }
        Row { Text("Shipping", Modifier.weight(1f)); Text(money(app, o.shipping)) }
        Row { Text("Total", Modifier.weight(1f), fontWeight = FontWeight.Bold); Text(money(app, o.total), fontWeight = FontWeight.Bold) }
        Text("Payment: ${o.paymentStatus.ifBlank { "-" }}", fontSize = 13.sp)

        SectionTitle("Status")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ORDER_STATUSES.forEach { s -> Chip(s, status == s) { status = s } }
        }
        Text("Confirming an order removes its items from stock once. Cancelling a confirmed order puts them back.",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))

        SectionTitle("Shipping")
        OutlinedTextField(courier, { courier = it }, label = { Text("Courier") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(tracking, { tracking = it }, label = { Text("Tracking number") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        OutlinedTextField(shipping, { shipping = it }, label = { Text("Shipping charge") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp))

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Ship with Shiprocket", fontWeight = FontWeight.SemiBold)
        ShipmentSection(app, o.id, o.orderStatus, o.trackingNo) { awb, courierName, pickup ->
            if (awb.isNotBlank()) tracking = awb
            if (courierName.isNotBlank()) courier = courierName
            if (pickup) status = "Shipped"
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                scope.launch {
                    app.repo.updateOrder(o.id, status, tracking.trim(), courier.trim(), shipping.toDoubleOrNull() ?: 0.0)
                    toast(ctx, "Order updated")
                    nav.back()
                }
            },
            modifier = Modifier.fillMaxWidth().height(54.dp)
        ) { Text("Save order") }
        Spacer(Modifier.height(32.dp))
    }
}

// Customers now live in Customers.kt (update 2).

// ================================================================== REPORTS
@Composable
fun ReportsScreen(app: App, nav: Nav) {
    val bills by app.repo.bills.collectAsStateWithLifecycle(emptyList())
    val orders by app.repo.orders.collectAsStateWithLifecycle(emptyList())
    val products by app.repo.products.collectAsStateWithLifecycle(emptyList())
    val returns by app.repo.returns.collectAsStateWithLifecycle(emptyList())
    var period by remember { mutableStateOf("today") }
    // which report is open: (kind, value). null = the summary.
    var detail by remember { mutableStateOf<Pair<String, String>?>(null) }
    val fmt = remember { SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()) }

    val from = when (period) {
        "today" -> startOfToday()
        "7" -> startOfToday() - 6L * 86_400_000
        "30" -> startOfToday() - 29L * 86_400_000
        else -> 0L
    }
    val b = bills.filter { it.createdAt >= from }
    val o = orders.filter { it.createdAt >= from && it.orderStatus != "Cancelled" }
    val posTotal = b.sumOf { it.total }
    val onlineTotal = o.sumOf { it.total }
    val rets = returns.filter { it.createdAt >= from && it.status != "REJECTED" }
    val refunds = rets.sumOf { it.refund }
    val byMethod = b.groupBy { it.method }.mapValues { e -> e.value.sumOf { it.total } }
    // best sellers: key = "article|name"
    val soldGroups = b.flatMap { bl -> parseBillItems(bl.itemsJson).map { bl to it } }
        .groupBy { it.second.article + "|" + it.second.name }
    val sold = soldGroups.map { (k, v) -> Triple(k, v.sumOf { it.second.qty }, v.sumOf { it.second.qty * it.second.price }) }
        .sortedByDescending { it.second }.take(10)
    val low = app.prefs.lowThreshold
    val lowList = products.flatMap { p -> p.variants.flatMap { v -> v.sizes.filter { it.qty <= low }.map { Triple(p, v, it) } } }
        .sortedBy { it.third.qty }.take(20)

    val d = detail
    if (d != null) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
            TextButton(onClick = { detail = null }) { Text("← रिपोर्ट पर वापस") }
            val (kind, value) = d
            when (kind) {
                "pos", "avg" -> {
                    SectionTitle("POS बिल (${b.size}) · कुल ${money(app, posTotal)}")
                    BillRowsList(app, b, nav, fmt)
                }
                "revenue" -> {
                    SectionTitle("POS बिल (${b.size}) · ${money(app, posTotal)}")
                    BillRowsList(app, b, nav, fmt)
                    SectionTitle("ऑनलाइन ऑर्डर (${o.size}) · ${money(app, onlineTotal)}")
                    OrderRowsList(app, o, nav, fmt)
                }
                "online" -> {
                    SectionTitle("ऑनलाइन ऑर्डर (${o.size}) · ${money(app, onlineTotal)}")
                    OrderRowsList(app, o, nav, fmt)
                }
                "refund", "net" -> {
                    SectionTitle("वापसी / एक्सचेंज (${rets.size}) · ${money(app, refunds)}")
                    ReturnRowsList(app, rets, fmt)
                    if (kind == "net") {
                        SectionTitle("POS बिल (${b.size}) · ${money(app, posTotal)}")
                        BillRowsList(app, b, nav, fmt)
                        SectionTitle("ऑनलाइन ऑर्डर (${o.size}) · ${money(app, onlineTotal)}")
                        OrderRowsList(app, o, nav, fmt)
                    }
                }
                "method" -> {
                    val list = b.filter { it.method == value }
                    SectionTitle("$value · ${list.size} बिल · ${money(app, list.sumOf { it.total })}")
                    BillRowsList(app, list, nav, fmt)
                }
                "item" -> {
                    // every POS bill that sold this article+name, with the pieces in it
                    val lines = soldGroups[value].orEmpty()
                    val qty = lines.sumOf { it.second.qty }
                    val amt = lines.sumOf { it.second.qty * it.second.price }
                    SectionTitle(value.substringAfter("|") + " (" + value.substringBefore("|") + ") · $qty pcs · ${money(app, amt)}")
                    lines.sortedByDescending { it.first.createdAt }.forEach { (bl, it) ->
                        Row(Modifier.fillMaxWidth().clickable { nav.go(Route.Receipt(bl.id)) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${bl.billNo} · ${it.colour} · साइज़ ${it.size}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text(fmt.format(Date(bl.createdAt)) + " · " + bl.method, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("${it.qty} pcs", fontSize = 13.sp)
                                Text(money(app, it.qty * it.price), fontWeight = FontWeight.Bold)
                            }
                        }
                        HorizontalDivider()
                    }
                    if (lines.isEmpty()) Text("इस अवधि में कोई बिक्री नहीं।", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                "stock" -> {
                    val p = products.firstOrNull { it.id == value }
                    if (p == null) Text("यह आइटम नहीं मिला।") else {
                        SectionTitle("${p.article} · ${p.name}".trim(' ', '·'))
                        p.variants.forEach { v ->
                            Text(v.colour, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                            v.sizes.forEach { s ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("साइज़ ${s.size}", Modifier.weight(1f))
                                    StockBadge(s.qty, low)
                                }
                            }
                        }
                        OutlinedButton(onClick = { nav.go(Route.ProductEdit(p.id)) }, modifier = Modifier.padding(top = 12.dp)) { Text("आइटम खोलें / बदलें") }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        return
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("today" to "Today", "7" to "7 days", "30" to "30 days", "all" to "All time").forEach { (k, l) ->
                Chip(l, period == k) { period = k }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReportCard("POS sales", money(app, posTotal), "${b.size} bills", Modifier.weight(1f)) { detail = "pos" to "" }
            ReportCard("Online orders", money(app, onlineTotal), "${o.size} orders", Modifier.weight(1f)) { detail = "online" to "" }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            ReportCard("Total revenue", money(app, posTotal + onlineTotal), "POS + online", Modifier.weight(1f)) { detail = "revenue" to "" }
            ReportCard("Average bill", money(app, if (b.isEmpty()) 0.0 else posTotal / b.size), "POS", Modifier.weight(1f)) { detail = "avg" to "" }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            ReportCard("Refunds", money(app, refunds), "${rets.size} return / exchange", Modifier.weight(1f)) { detail = "refund" to "" }
            ReportCard("Net revenue", money(app, posTotal + onlineTotal - refunds), "after refunds", Modifier.weight(1f)) { detail = "net" to "" }
        }

        SectionTitle("POS sales by payment method")
        if (byMethod.isEmpty()) Text("No sales in this period.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        byMethod.entries.sortedByDescending { it.value }.forEach { (m, v) ->
            Row(Modifier.fillMaxWidth().clickable { detail = "method" to m }.padding(vertical = 4.dp)) { Text(m, Modifier.weight(1f)); Text(money(app, v), fontWeight = FontWeight.SemiBold) }
            LinearProgressIndicator(progress = { if (posTotal > 0) (v / posTotal).toFloat() else 0f }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
        }

        SectionTitle("Best sellers (POS)")
        if (sold.isEmpty()) Text("No sales in this period.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        sold.forEach { (key, qty, rev) ->
            Row(Modifier.fillMaxWidth().clickable { detail = "item" to key }.padding(vertical = 6.dp)) {
                Text(key.substringAfter("|") + " (" + key.substringBefore("|") + ")", Modifier.weight(1f), maxLines = 1)
                Text("$qty pcs · ${money(app, rev)}", fontSize = 13.sp)
            }
        }

        SectionTitle("Needs restocking (stock $low or less)")
        if (lowList.isEmpty()) Text("All good - nothing low.", color = GreenOk)
        lowList.forEach { (p, v, s) ->
            Row(Modifier.fillMaxWidth().clickable { detail = "stock" to p.id }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${p.article} · ${v.colour} / ${s.size}", Modifier.weight(1f), maxLines = 1)
                StockBadge(s.qty, low)
            }
        }

        SectionTitle("Bills")
        if (b.isEmpty()) Text("No bills in this period.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        BillRowsList(app, b.take(50), nav, fmt)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun BillRowsList(app: App, list: List<BillE>, nav: Nav, fmt: SimpleDateFormat) {
    list.forEach { bill ->
        Row(Modifier.fillMaxWidth().clickable { nav.go(Route.Receipt(bill.id)) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(bill.billNo, fontWeight = FontWeight.SemiBold)
                Text("${fmt.format(Date(bill.createdAt))} · ${bill.method}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(money(app, bill.total), fontWeight = FontWeight.Bold)
                if (!bill.synced) Text("waiting to sync", fontSize = 10.sp, color = AmberLow)
            }
        }
        HorizontalDivider()
    }
    if (list.isEmpty()) Text("कोई बिल नहीं।", color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun OrderRowsList(app: App, list: List<OrderE>, nav: Nav, fmt: SimpleDateFormat) {
    list.forEach { order ->
        Row(Modifier.fillMaxWidth().clickable { nav.go(Route.OrderDetail(order.id)) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("ऑर्डर ${order.id.takeLast(6).uppercase()}", fontWeight = FontWeight.SemiBold)
                Text("${fmt.format(Date(order.createdAt))} · ${order.orderStatus}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(money(app, order.total), fontWeight = FontWeight.Bold)
        }
        HorizontalDivider()
    }
    if (list.isEmpty()) Text("कोई ऑर्डर नहीं।", color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ReturnRowsList(app: App, list: List<ReturnE>, fmt: SimpleDateFormat) {
    list.forEach { r ->
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${if (r.kind == "EXCHANGE") "एक्सचेंज" else "वापसी"} · ${r.refType} ${r.refId.takeLast(6).uppercase()}", fontWeight = FontWeight.SemiBold)
                Text("${fmt.format(Date(r.createdAt))} · ${r.reason}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(money(app, r.refund), fontWeight = FontWeight.Bold)
        }
        HorizontalDivider()
    }
    if (list.isEmpty()) Text("कोई वापसी नहीं।", color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ReportCard(title: String, value: String, sub: String, modifier: Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(title, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(sub, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
