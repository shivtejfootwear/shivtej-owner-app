package com.shivtej.owner.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shivtej.owner.App
import com.shivtej.owner.data.Product
import com.shivtej.owner.data.titleOf
import kotlinx.coroutines.launch

/** One size that still has pieces without a barcode. */
private data class NoBcRow(
    val pid: String, val title: String, val article: String, val colour: String, val size: String,
    val total: Int, val barcoded: Int
) {
    val missing: Int get() = total - barcoded
}

/** The size a scan is being assigned to. */
private data class NoBcTarget(val pid: String, val colour: String, val size: String, val label: String)

/** Every size where the stock is more than the barcoded pieces: those pieces have no barcode yet. */
private fun noBarcodeRows(products: List<Product>): List<NoBcRow> {
    val out = ArrayList<NoBcRow>()
    products.forEach { p ->
        val title = titleOf(p.brand, p.article, p.name)
        p.variants.forEach { v ->
            v.sizes.forEach { s ->
                val barcoded = minOf(s.qty, s.barcodes.size)
                if (s.qty > barcoded) out.add(NoBcRow(p.id, title, p.article, v.colour, s.size, s.qty, barcoded))
            }
        }
    }
    return out.sortedWith(compareBy({ it.title }, { it.colour }))
}

/**
 * Dashboard shortcut "No barcode pieces": every piece in stock that has no barcode yet, by item and colour.
 * Pressing "बारकोड लगाएँ" on a size and scanning the piece's label gives it that barcode;
 * one less piece is then in the list, and when none is left the size goes off the list.
 */
@Composable
fun NoBarcodeScreen(app: App) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val products by app.repo.products.collectAsStateWithLifecycle(emptyList())
    val rows = remember(products) { noBarcodeRows(products) }
    var query by remember { mutableStateOf("") }
    var target by remember { mutableStateOf<NoBcTarget?>(null) }

    val shown = remember(rows, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) rows else rows.filter {
            it.title.lowercase().contains(q) || it.colour.lowercase().contains(q) || it.size.lowercase().contains(q)
        }
    }
    val totalPieces = rows.sumOf { it.total }
    val missingPieces = rows.sumOf { it.missing }
    // groups: one card per item and colour
    val groups = remember(shown) { shown.groupBy { it.pid to it.colour }.values.toList() }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("बिना बारकोड वाले पीस: $missingPieces", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("स्टॉक में कुल पीस: $totalPieces · बारकोड वाले: ${totalPieces - missingPieces}",
                    fontSize = 13.sp)
                Text("किसी साइज़ पर \"बारकोड लगाएँ\" दबाइए, फिर उस पीस का लेबल स्कैन कीजिए। स्कैन होते ही वह पीस लिस्ट से हट जाएगा।",
                    fontSize = 12.sp)
            }
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            label = { Text("आर्टिकल, रंग या साइज़ खोजें") },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        )

        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("सभी पीस पर बारकोड लगा है। कोई पीस बाकी नहीं।", fontSize = 15.sp)
            }
        } else if (groups.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("खोज से कोई आइटम नहीं मिला", fontSize = 15.sp)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(groups, key = { g -> g.first().pid + "|" + g.first().colour }) { group ->
                    val first = group.first()
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(first.title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text("रंग: ${first.colour}", fontSize = 13.sp)
                            HorizontalDivider(Modifier.padding(vertical = 6.dp))
                            group.forEach { r ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("साइज़ ${r.size}", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                        Text("बिना बारकोड: ${r.missing} (कुल ${r.total}, बारकोड वाले ${r.barcoded})", fontSize = 12.sp)
                                    }
                                    Button(onClick = {
                                        target = NoBcTarget(r.pid, r.colour, r.size, "${r.title} · ${r.colour} · ${r.size}")
                                    }) { Text("बारकोड लगाएँ") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    target?.let { t ->
        ScannerDialog(
            title = "Scan barcode for ${t.label}",
            onResult = { code ->
                val c = code.trim()
                target = null
                if (c.isNotEmpty()) scope.launch {
                    val hit = app.repo.lookup(c)
                    when {
                        hit != null && hit.product.id == t.pid && hit.colour == t.colour && hit.size.size == t.size -> {
                            Beeps.again(ctx); toast(ctx, "यह बारकोड इसी साइज़ पर पहले से लगा है")
                        }
                        hit != null -> {
                            Beeps.fail(ctx)
                            toast(ctx, "यह बारकोड दूसरे आइटम पर लगा है: ${titleOf(hit.product.brand, hit.product.article, hit.product.name)} ${hit.colour} ${hit.size.size}")
                        }
                        else -> runCatching { app.repo.assignBarcode(t.pid, t.colour, t.size, c) }
                            .onSuccess { Beeps.ok(ctx); toast(ctx, "बारकोड लग गया: ${t.label}") }
                            .onFailure { Beeps.fail(ctx); toast(ctx, it.message ?: "बारकोड नहीं लगा") }
                    }
                }
            },
            onDismiss = { target = null }
        )
    }
}
