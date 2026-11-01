@file:OptIn(ExperimentalMaterial3Api::class)

package com.shivtej.owner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shivtej.owner.App
import com.shivtej.owner.data.*
import java.text.SimpleDateFormat
import java.util.Locale

/** One piece sold: from an offline (POS) bill, or from an online order. */
data class SaleLine(
    val article: String, val colour: String, val size: String, val qty: Int, val at: Long,
    val customer: String, val mobile: String, val billId: String, val orderId: String, val ref: String, val online: Boolean
)

/** Every sale of every piece: POS bills (not deleted) and online orders (not cancelled), newest first. */
fun salesOf(bills: List<BillE>, orders: List<OrderE>, products: List<Product>): List<SaleLine> {
    val out = ArrayList<SaleLine>()
    bills.forEach { b ->
        parseBillItems(b.itemsJson).forEach { l ->
            out.add(SaleLine(l.article.trim(), l.colour.trim(), l.size.trim(), l.qty, b.createdAt,
                b.customerName.ifBlank { "Walk-in customer" }, b.customerMobile, b.id, "",
                b.billNo.ifBlank { "Bill" }, false))
        }
    }
    orders.filter { !it.orderStatus.contains("CANCEL", ignoreCase = true) }.forEach { o ->
        parseOrderItems(o.itemsJson, products).forEach { l ->
            out.add(SaleLine(l.article.trim(), l.colour.trim(), l.size.trim(), l.qty, o.createdAt,
                custField(o.customerJson, "name").ifBlank { "Online customer" }, custField(o.customerJson, "mobile", "phone"),
                "", o.id, "Order " + o.id.takeLast(6).uppercase(), true))
        }
    }
    return out.sortedByDescending { it.at }
}

/** Small colour dot, so the colour is seen at a glance next to a size. */
fun colourDot(name: String): Color {
    val n = name.lowercase()
    return when {
        "white" in n || "off" in n -> Color(0xFFFFFFFF)
        "cream" in n || "ivory" in n -> Color(0xFFFFFDD0)
        "beige" in n || "nude" in n -> Color(0xFFD7C4A3)
        "camal" in n || "camel" in n || "tan" in n -> Color(0xFFC19A6B)
        "black" in n -> Color(0xFF212121)
        "brown" in n || "coffee" in n -> Color(0xFF6D4C41)
        "wine" in n || "maroon" in n -> Color(0xFF7B1F3A)
        "red" in n -> Color(0xFFD32F2F)
        "pink" in n || "peach" in n -> Color(0xFFF8BBD0)
        "navy" in n -> Color(0xFF1A237E)
        "blue" in n -> Color(0xFF1E88E5)
        "green" in n || "olive" in n -> Color(0xFF2E7D32)
        "yellow" in n || "gold" in n -> Color(0xFFFBC02D)
        "orange" in n -> Color(0xFFEF6C00)
        "grey" in n || "gray" in n || "silver" in n -> Color(0xFF9E9E9E)
        else -> Color(0xFFBDBDBD)
    }
}

@Composable
private fun ColourDot(name: String, size: Int) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(colourDot(name)).border(1.dp, Color(0x33000000), CircleShape))
}

// ================================================================== ITEM HISTORY (who bought an item)
@Composable
fun ItemHistoryScreen(app: App, nav: Nav) {
    val products by app.repo.products.collectAsStateWithLifecycle(emptyList())
    val bills by app.repo.bills.collectAsStateWithLifecycle(emptyList())
    val orders by app.repo.orders.collectAsStateWithLifecycle(emptyList())
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<Product?>(null) }
    var colour by remember { mutableStateOf("") }
    val sales = remember(bills, orders, products) { salesOf(bills, orders, products) }
    val q = query.trim()
    val matches = remember(q, products) {
        if (q.isBlank()) emptyList() else products.filter { it.article.contains(q, true) || it.name.contains(q, true) }.take(8)
    }
    val current = picked ?: products.firstOrNull { it.article.equals(q, ignoreCase = true) }

    Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Type the article, pick the colour and the size. You will see who bought it.", fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(query, { query = it; picked = null; colour = "" }, label = { Text("Article code") }, singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, null) }, modifier = Modifier.fillMaxWidth())

        if (picked == null) matches.forEach { p ->
            Card(Modifier.fillMaxWidth().clickable { picked = p; colour = p.variants.firstOrNull()?.colour ?: "" }) {
                Column(Modifier.padding(10.dp)) {
                    Text(p.article.ifBlank { p.name }, fontWeight = FontWeight.Bold)
                    Text("${p.name} · ${p.variants.size} colour(s)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (current == null) {
            if (q.isBlank()) EmptyState("Start typing an article code.")
            else if (matches.isEmpty()) EmptyState("No article found with this code.")
        } else {
            val colours = current.variants.map { it.colour }
            val selColour = if (colour in colours) colour else colours.firstOrNull().orEmpty()
            val soldHere = sales.filter { it.article.equals(current.article, true) }
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(current.article.ifBlank { current.name }, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("${soldHere.sumOf { it.qty }} pcs sold in ${soldHere.size} sales", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (picked != null) TextButton(onClick = { picked = null }) { Text("Change") }
                }
            }

            Text("Colour", fontWeight = FontWeight.SemiBold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                colours.forEach { c ->
                    FilterChip(selected = selColour == c, onClick = { colour = c }, label = { Text(c) },
                        leadingIcon = { ColourDot(c, 12) })
                }
            }

            Text("Size (tap to see who bought it)", fontWeight = FontWeight.SemiBold)
            val variantSizes = current.variants.firstOrNull { it.colour == selColour }?.sizes.orEmpty()
            val soldSizes = soldHere.filter { it.colour.equals(selColour, true) }.map { it.size }
            val sizes = (variantSizes.map { it.size } + soldSizes).filter { it.isNotBlank() }.distinct()
                .sortedWith(compareBy<String>({ it.toIntOrNull() ?: Int.MAX_VALUE }, { it }))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                sizes.forEach { sz ->
                    val sold = soldHere.filter { it.colour.equals(selColour, true) && it.size == sz }.sumOf { it.qty }
                    val stock = variantSizes.firstOrNull { it.size == sz }?.qty
                    FilterChip(selected = false,
                        onClick = { nav.go(Route.ItemSales(current.article, selColour, sz)) },
                        label = {
                            Column {
                                Text(sz, fontWeight = FontWeight.Bold)
                                Text("sold $sold" + (if (stock != null) " · stock $stock" else ""), fontSize = 10.sp)
                            }
                        },
                        leadingIcon = { ColourDot(selColour, 10) })
                }
            }
            if (sizes.isEmpty()) EmptyState("No size for this colour yet.")
        }
    }
}

// ================================================================== SALES OF ONE SIZE (customers)
@Composable
fun ItemSalesScreen(app: App, nav: Nav, article: String, colour: String, size: String) {
    val products by app.repo.products.collectAsStateWithLifecycle(emptyList())
    val bills by app.repo.bills.collectAsStateWithLifecycle(emptyList())
    val orders by app.repo.orders.collectAsStateWithLifecycle(emptyList())
    val fmt = remember { SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.ENGLISH) }
    val lines = remember(bills, orders, products, article, colour, size) {
        salesOf(bills, orders, products).filter {
            it.article.equals(article, true) && it.colour.equals(colour, true) && it.size == size
        }
    }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(12.dp)) {
                Text("$article · $colour / $size", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("${lines.sumOf { it.qty }} pcs · ${lines.size} sales", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (lines.isEmpty()) EmptyState("Nobody has bought this size yet.")
        LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 6.dp, bottom = 24.dp)) {
            itemsIndexed(lines) { _, l ->
                Row(Modifier.fillMaxWidth().clickable {
                    if (l.billId.isNotBlank()) nav.go(Route.Receipt(l.billId)) else nav.go(Route.OrderDetail(l.orderId))
                }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(l.customer, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable(enabled = l.mobile.isNotBlank()) { nav.go(Route.CustomerDetail(l.mobile)) })
                        Text(fmt.format(java.util.Date(l.at)) + " · " + l.ref +
                            (if (l.online) " · online" else " · shop"), fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (l.mobile.isNotBlank()) Text(l.mobile, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("${l.qty} pcs", fontWeight = FontWeight.Bold, color = BrandRed)
                }
                HorizontalDivider()
            }
        }
    }
}
