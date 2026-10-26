@file:OptIn(ExperimentalMaterial3Api::class)

package com.shivtej.owner.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Checklist
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
    var sizeFilter by remember { mutableStateOf("all") }
    var showCategories by remember { mutableStateOf(false) }
    val low = app.prefs.lowThreshold
    val allSizes = remember(products) { products.flatMap { p -> p.variants.flatMap { v -> v.sizes.map { it.size } } }
        .distinct().sortedBy { it.toDoubleOrNull() ?: 9999.0 } }

    val shown = products.filter { p ->
        val q = query.trim().lowercase()
        val matches = q.isEmpty() || listOf(p.name, p.article, p.brand, p.category).any { it.lowercase().contains(q) } ||
            p.variants.any { v -> v.colour.lowercase().contains(q) || v.sizes.any { s -> (s.barcodes + s.barcode).any { it.lowercase().contains(q) } } }
        val ok = when (filter) {
            "active" -> p.active
            "inactive" -> !p.active
            "low" -> p.variants.any { v -> v.sizes.any { it.qty in 1..low } }
            "out" -> p.totalStock <= 0 || p.variants.any { v -> v.sizes.any { it.qty <= 0 } }
            else -> true
        }
        val sizeOk = sizeFilter == "all" || p.variants.any { v -> v.sizes.any { it.size == sizeFilter } }
        matches && ok && sizeOk
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SearchField(query, { query = it }, "Search name, article, brand, colour, barcode", Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                // product type > category: the category box (ProductTypes / CATEGORY)
                FilledTonalButton(onClick = { showCategories = true }, shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Filled.Checklist, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("CATEGORY", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("all" to "All", "active" to "Active", "inactive" to "Inactive", "low" to "Low stock", "out" to "Out of stock")
                    .forEach { (k, l) -> Chip(l, filter == k) { filter = k } }
            }
            SizeFilterRow(allSizes, sizeFilter) { sizeFilter = it }
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
    if (showCategories) CategoryDialog(app, products) { showCategories = false }
}
