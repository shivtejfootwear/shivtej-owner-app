@file:OptIn(ExperimentalMaterial3Api::class)

package com.shivtej.owner.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shivtej.owner.App
import kotlinx.coroutines.launch
import java.util.Calendar

fun startOfToday(): Long {
    val c = Calendar.getInstance()
    c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
}

private data class StatCard(val title: String, val value: String, val sub: String, val color: Color, val go: Route)

@Composable
fun DashboardScreen(app: App, nav: Nav) {
    val products by app.repo.products.collectAsStateWithLifecycle(emptyList())
    val bills by app.repo.bills.collectAsStateWithLifecycle(emptyList())
    val orders by app.repo.orders.collectAsStateWithLifecycle(emptyList())
    val failed by app.repo.failedOps.collectAsStateWithLifecycle(0)
    val st by app.sync.state.collectAsStateWithLifecycle()
    val online by app.net.online.collectAsStateWithLifecycle()

    val low = app.prefs.lowThreshold
    val sizes = products.flatMap { p -> p.variants.flatMap { it.sizes } }
    val lowCount = sizes.count { it.qty in 1..low }
    val outCount = sizes.count { it.qty <= 0 }
    val start = startOfToday()
    val todayBills = bills.filter { it.createdAt >= start }
    val pending = orders.count { it.orderStatus == "New" }

    val cards = listOf(
        StatCard("Total Products", products.size.toString(), "${products.count { it.active }} active", Color(0xFF1565C0), Route.Products("all")),
        StatCard("Total Stock", sizes.sumOf { it.qty }.toString(), "pairs in stock", Color(0xFF2E7D32), Route.Stock(0, "all")),
        StatCard("Low Stock", lowCount.toString(), "sizes at $low or less", Color(0xFFEF6C00), Route.Stock(0, "low")),
        StatCard("Out of Stock", outCount.toString(), "sizes at 0", Color(0xFFC62828), Route.Stock(0, "out")),
        StatCard("Today's POS Sales", money(app, todayBills.sumOf { it.total }), "shop sales today", Color(0xFF6A1B9A), Route.Reports),
        StatCard("Today's Bills", todayBills.size.toString(), "bills today", Color(0xFF00838F), Route.Reports),
        StatCard("Website Orders", orders.size.toString(), "all orders", Color(0xFF283593), Route.Orders("all")),
        StatCard("Pending Orders", pending.toString(), "new, not confirmed", Color(0xFFAD1457), Route.Orders("pending"))
    )

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        if (!online) {
            Banner("You are offline. Everything keeps working and will sync when internet returns.", Color(0xFFFFE0B2))
        }
        st.lastError?.let { if (online) Banner(it, Color(0xFFFFCDD2)) }
        if (failed > 0) Banner("$failed change(s) could not be synced. Open Settings > Sync status.", Color(0xFFFFCDD2))
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { nav.go(Route.Pos) }, modifier = Modifier.weight(1f).height(52.dp)) { Text("New Bill") }
            OutlinedButton(onClick = { nav.go(Route.ProductEdit(null)) }, modifier = Modifier.weight(1f).height(52.dp)) { Text("Add Item") }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(cards) { c ->
                Card(onClick = { nav.go(c.go) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(c.title, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(c.value, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = c.color)
                        Text(c.sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun Banner(text: String, bg: Color) {
    Surface(color = bg, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Text(text, color = Color.Black, fontSize = 13.sp, modifier = Modifier.padding(10.dp))
    }
}

// ------------------------------------------------------------------ products list
@Composable
fun ProductsScreen(app: App, nav: Nav, initialFilter: String) {
    val products by app.repo.products.collectAsStateWithLifecycle(emptyList())
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(initialFilter) }
    val low = app.prefs.lowThreshold

    val shown = products.filter { p ->
        val q = query.trim().lowercase()
        val matches = q.isEmpty() || listOf(p.name, p.article, p.brand, p.category).any { it.lowercase().contains(q) } ||
            p.variants.any { v -> v.colour.lowercase().contains(q) || v.sizes.any { it.barcode.lowercase().contains(q) } }
        val ok = when (filter) {
            "active" -> p.active
            "inactive" -> !p.active
            "low" -> p.variants.any { v -> v.sizes.any { it.qty in 1..low } }
            "out" -> p.totalStock <= 0 || p.variants.any { v -> v.sizes.any { it.qty <= 0 } }
            else -> true
        }
        matches && ok
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            SearchField(query, { query = it }, "Search name, article, brand, colour, barcode", Modifier.padding(top = 8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("all" to "All", "active" to "Active", "inactive" to "Inactive", "low" to "Low stock", "out" to "Out of stock")
                    .forEach { (k, l) -> Chip(l, filter == k) { filter = k } }
            }
            if (shown.isEmpty()) EmptyState(if (products.isEmpty()) "No products yet. Tap + to add your first item." else "No products match.")
            LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                items(shown, key = { it.id }) { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable { nav.go(Route.ProductEdit(p.id)) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Thumb(p.allImages.firstOrNull(), 64.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text("${p.article} · ${p.brand.ifBlank { "-" }} · ${p.category.ifBlank { "-" }}", fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            Text("${money(app, p.price)}  (MRP ${money(app, p.mrp)})", fontSize = 13.sp)
                            Text("${p.variants.size} colour(s)" + if (!p.active) " · INACTIVE" else "", fontSize = 12.sp,
                                color = if (p.active) MaterialTheme.colorScheme.onSurfaceVariant else RedOut)
                        }
                        StockBadge(p.totalStock, low)
                    }
                    HorizontalDivider()
                }
            }
        }
        FloatingActionButton(
            onClick = { nav.go(Route.ProductEdit(null)) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) { Icon(Icons.Filled.Add, "Add item") }
    }
}
