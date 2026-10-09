@file:OptIn(ExperimentalMaterial3Api::class)

package com.shivtej.owner.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun fmtNum(d: Double): String =
    if (d == Math.floor(d)) d.toLong().toString() else String.format(Locale.US, "%.2f", d)

// ================================================================== RETURN / EXCHANGE FORM
/** refType = "ORDER" (website order id) or "BILL" (POS bill id). */
@Composable
fun ReturnFormScreen(app: App, nav: Nav, refType: String, refId: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val products by app.repo.products.collectAsStateWithLifecycle(emptyList())
    val orders by app.repo.orders.collectAsStateWithLifecycle(emptyList())
    val allReturns by app.repo.returns.collectAsStateWithLifecycle(emptyList())
    var bill by remember { mutableStateOf<BillE?>(null) }
    LaunchedEffect(refId) { if (refType == "BILL") bill = app.repo.bill(refId) }

    val itemsJson: String? = if (refType == "BILL") bill?.itemsJson else orders.firstOrNull { it.id == refId }?.itemsJson
    if (itemsJson == null) { EmptyState("Loading..."); return }
    val saleTitle = if (refType == "BILL") "Bill ${bill?.billNo ?: ""}" else "Order #$refId"

    val sold = remember(itemsJson, products) {
        parseRetLines(itemsJson).map { l ->
            val p = products.firstOrNull { it.id == l.productId }
            l.copy(
                name = l.name.ifBlank { p?.name ?: "Item" },
                article = l.article.ifBlank { p?.article ?: "" },
                price = if (l.price > 0) l.price else (p?.price ?: 0.0)
            )
        }.groupBy { retLineKey(it) }.map { (_, v) -> v.first().copy(qty = v.sumOf { it.qty }) }
    }
    val earlier = allReturns.filter { it.refType == refType && it.refId == refId }
    val doneQty = remember(earlier) {
        earlier.filter { it.status != "REJECTED" }.flatMap { parseRetLines(it.itemsInJson) }.groupBy { retLineKey(it) }.mapValues { e -> e.value.sumOf { it.qty } }
    }

    var kind by remember { mutableStateOf("RETURN") }
    val qtys = remember { mutableStateMapOf<String, Int>() }
    val replacements = remember { mutableStateListOf<RetLine>() }
    var reason by remember { mutableStateOf("") }
    var restock by remember { mutableStateOf(true) }
    var refundText by remember { mutableStateOf("") }
    var refundTouched by remember { mutableStateOf(false) }
    var pickProduct by remember { mutableStateOf(false) }
    var pickVariantOf by remember { mutableStateOf<Product?>(null) }
    var busy by remember { mutableStateOf(false) }
    var cashCap by remember { mutableStateOf(0.0) }      // most cash that may go back (the rest reduces the udhaar)

    val backValue = sold.sumOf { (qtys[retLineKey(it)] ?: 0) * it.price }
    val outValue = if (kind == "EXCHANGE") replacements.sumOf { it.qty * it.price } else 0.0
    LaunchedEffect(refType, refId, backValue, outValue) {
        cashCap = app.repo.maxCashRefund(refType, refId, backValue, outValue)
    }
    val net = backValue - outValue                       // > 0 refund, < 0 the customer pays the difference
    val autoRefund = if (net > 0) minOf(net, cashCap) else 0.0
    val customerPays = kind == "EXCHANGE" && net < 0
    val inLines = sold.filter { (qtys[retLineKey(it)] ?: 0) > 0 }.map { it.copy(qty = qtys[retLineKey(it)] ?: 0) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text("Return / Exchange", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(saleTitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("Return (refund)", kind == "RETURN") { kind = "RETURN" }
            Chip("Exchange", kind == "EXCHANGE") { kind = "EXCHANGE" }
        }

        SectionTitle("Items coming back")
        if (sold.isEmpty()) Text("This sale has no item details.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        sold.forEach { l ->
            val k = retLineKey(l)
            val done = doneQty[k] ?: 0
            val left = (l.qty - done).coerceAtLeast(0)
            val cur = qtys[k] ?: 0
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(l.name, fontWeight = FontWeight.SemiBold)
                    Text("${l.article} · ${l.colour} / Size ${l.size} · ${money(app, l.price)}", fontSize = 12.sp)
                    Text("Sold ${l.qty}" + (if (done > 0) " · already returned $done" else ""), fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (left == 0) {
                    Text("nothing left", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    IconButton(onClick = { if (cur > 0) qtys[k] = cur - 1 }) { Icon(Icons.Filled.Remove, "Less") }
                    Text("$cur", fontWeight = FontWeight.Bold)
                    IconButton(onClick = { if (cur < left) qtys[k] = cur + 1 }) { Icon(Icons.Filled.Add, "More") }
                }
            }
            HorizontalDivider()
        }

        if (kind == "EXCHANGE") {
            SectionTitle("Replacement items")
            replacements.forEachIndexed { i, r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.name, fontWeight = FontWeight.SemiBold)
                        Text("${r.article} · ${r.colour} / Size ${r.size} · Qty ${r.qty}", fontSize = 12.sp)
                    }
                    Text(money(app, r.price * r.qty))
                    IconButton(onClick = { replacements.removeAt(i) }) { Icon(Icons.Filled.Delete, "Remove") }
                }
            }
            OutlinedButton(onClick = { pickProduct = true }) {
                Icon(Icons.Filled.Add, null); Spacer(Modifier.width(4.dp)); Text("Add replacement item")
            }
        }

        SectionTitle("Details")
        OutlinedTextField(reason, { reason = it }, label = { Text("Reason (optional)") }, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Switch(checked = restock, onCheckedChange = { restock = it })
            Spacer(Modifier.width(8.dp))
            Column {
                Text("Put returned items back in stock")
                Text("Turn this off for damaged items", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        SectionTitle("Money")
        Row { Text("Items coming back", Modifier.weight(1f)); Text(money(app, backValue)) }
        if (kind == "EXCHANGE") Row { Text("Replacement items", Modifier.weight(1f)); Text(money(app, outValue)) }
        if (customerPays) {
            Text("Customer pays the difference: ${money(app, -net)}", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        } else {
            OutlinedTextField(
                value = if (refundTouched) refundText else fmtNum(autoRefund),
                onValueChange = { refundText = it; refundTouched = true },
                label = { Text("Refund to customer") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
        }
        Text("The refund is only recorded here. Hand the money back yourself. Cash refund allowed: " +
            money(app, cashCap) + ". The rest of the value reduces the customer's udhaar.", fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))

        Spacer(Modifier.height(16.dp))
        Button(
            enabled = !busy && inLines.isNotEmpty() && (kind == "RETURN" || replacements.isNotEmpty()),
            onClick = {
                busy = true
                scope.launch {
                    try {
                        val refund = if (customerPays) net
                        else (if (refundTouched) (refundText.toDoubleOrNull() ?: 0.0) else autoRefund).coerceAtLeast(0.0)
                        app.repo.processReturn(
                            refType, refId, kind, reason.trim(), restock, refund, inLines,
                            if (kind == "EXCHANGE") replacements.toList() else emptyList()
                        )
                        toast(ctx, if (kind == "EXCHANGE") "Exchange saved" else "Return saved")
                        nav.back()
                    } catch (e: Exception) {
                        busy = false
                        toast(ctx, e.message ?: "Could not save")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(54.dp)
        ) { Text(if (kind == "EXCHANGE") "Confirm exchange" else "Confirm return") }

        if (earlier.isNotEmpty()) {
            val fmt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
            SectionTitle("Earlier returns on this sale")
            earlier.forEach { r ->
                Text((if (r.kind == "EXCHANGE") "Exchange" else "Return") + " · " + fmt.format(Date(r.createdAt)) +
                    " · " + parseRetLines(r.itemsInJson).sumOf { it.qty } + " pc" +
                    (if (r.status == "REJECTED") " · REJECTED by server, not counted" else "") +
                    (if (r.pendingSync) " · waiting to sync" else ""), fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(32.dp))
    }

    if (pickProduct) {
        ProductSearchDialog(app, products, "Replacement item", { pickProduct = false }) { p ->
            pickProduct = false
            pickVariantOf = p
        }
    }
    pickVariantOf?.let { p ->
        VariantPickerDialog(app, p, "Add", { pickVariantOf = null }) { colour, size, qty ->
            pickVariantOf = null
            val same = replacements.indexOfFirst { it.productId == p.id && it.colour == colour && it.size == size.size }
            val already = if (same >= 0) replacements[same].qty else 0
            if (already + qty > size.qty) {
                toast(ctx, "Only ${size.qty} in stock")
            } else if (same >= 0) {
                replacements[same] = replacements[same].copy(qty = already + qty)
            } else {
                replacements.add(RetLine(p.id, p.article, p.name, colour, size.size, qty, p.price))
            }
        }
    }
}

// ================================================================== ALL RETURNS & EXCHANGES
@Composable
fun ReturnsScreen(app: App, nav: Nav) {
    val list by app.repo.returns.collectAsStateWithLifecycle(emptyList())
    val bills by app.repo.bills.collectAsStateWithLifecycle(emptyList())
    val fmt = remember { SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()) }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        if (list.isEmpty()) EmptyState("No returns or exchanges yet. Open an order or a bill and tap Return / Exchange.")
        LazyColumn {
            items(list, key = { it.id }) { r ->
                val pieces = parseRetLines(r.itemsInJson).sumOf { it.qty }
                val ref = if (r.refType == "BILL") "Bill " + (bills.firstOrNull { it.id == r.refId }?.billNo ?: "") else "Order #" + r.refId
                Row(
                    Modifier.fillMaxWidth().clickable {
                        nav.go(if (r.refType == "BILL") Route.Receipt(r.refId) else Route.OrderDetail(r.refId))
                    }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text((if (r.kind == "EXCHANGE") "Exchange" else "Return") + " · " + ref, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text("$pieces pc" + (if (r.reason.isNotBlank()) " · " + r.reason else "") + (if (!r.restock) " · not restocked" else ""),
                            fontSize = 13.sp, maxLines = 1)
                        Text(fmt.format(Date(r.createdAt)), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (r.status == "REJECTED") Text("REJECTED by server: ${r.rejectReason}", fontSize = 11.sp, color = RedOut, maxLines = 2)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        if (r.status == "REJECTED") Text("Not counted", fontWeight = FontWeight.Bold, color = RedOut)
                        else Text(if (r.refund < 0) "Paid " + money(app, -r.refund) else "Refund " + money(app, r.refund), fontWeight = FontWeight.Bold)
                        if (r.pendingSync) Text("waiting to sync", fontSize = 10.sp, color = AmberLow)
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
