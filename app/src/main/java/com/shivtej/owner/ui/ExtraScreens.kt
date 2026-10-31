package com.shivtej.owner.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.heightIn
import kotlin.math.roundToInt
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import com.shivtej.owner.data.ChangeE
import com.shivtej.owner.data.SizeRequestE
import com.shivtej.owner.data.parsePurchaseLines
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Pieces with this many or fewer left are put on the supplier order list. */
private const val LOW_STOCK_LIMIT = 1

private fun openWhatsApp(ctx: Context, mobile: String, text: String) {
    val digits = mobile.filter { it.isDigit() }.takeLast(10)
    val url = if (digits.length == 10) "https://wa.me/91$digits?text=${Uri.encode(text)}"
        else "https://wa.me/?text=${Uri.encode(text)}"
    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

// ------------------------------------------------------------------ 1. change log
@Composable
fun ChangeLogScreen(app: App) {
    val list by app.repo.changeLog.collectAsStateWithLifecycle(emptyList())
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("Every bill deletion and every price change, newest first. Recorded on this phone.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (list.isEmpty()) EmptyState("No change recorded yet.")
        LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
            items(list, key = { it.id }) { c: ChangeE ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = if (c.kind == "BILL") Color(0xFFFFEBEE) else MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(if (c.kind == "BILL") "BILL DELETED" else "PRICE CHANGED", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            color = if (c.kind == "BILL") Color(0xFFC62828) else MaterialTheme.colorScheme.primary)
                        Text(c.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(c.at)),
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 2. supplier order list (brand wise) + order pad
/** A piece that is low on stock. supplierId is only part of the key ("" = no purchase bill has it). */
private data class OrderRow(val supplierId: String, val brand: String, val category: String, val article: String,
                            val colour: String, val size: String, val qty: Int) {
    /** same key for the same piece every time (used for the "do not order" list and the pad) */
    val key: String get() = "$supplierId|${article.trim().lowercase()}|${colour.trim().lowercase()}|$size"
}

/** One line on the order pad: a piece the owner has decided to order, with the quantity to order. */
private data class PadRow(val key: String, val brand: String, val category: String, val article: String,
                          val colour: String, val size: String, val qty: Int)

private const val PAD_SEP = "\u001F"

private fun padEncode(list: List<PadRow>): String = list.joinToString("\n") {
    listOf(it.key, it.brand, it.category, it.article, it.colour, it.size, it.qty.toString()).joinToString(PAD_SEP)
}

private fun padDecode(s: String): List<PadRow> = s.lines().filter { it.isNotBlank() }.mapNotNull { line ->
    val f = line.split(PAD_SEP)
    if (f.size < 7) null else PadRow(f[0], f[1], f[2], f[3], f[4], f[5], f[6].toIntOrNull()?.coerceAtLeast(1) ?: 1)
}

/** Short label on screen only: SENIOR MEN shows as SR MEN. The data name stays the same. */
private fun srLabel(cat: String): String = cat.replaceFirst("SENIOR ", "SR ")

private fun brandOf(s: String): String = s.trim().ifBlank { "BRAND NOT SET" }

@Composable
fun SupplierOrderScreen(app: App) {
    val ctx = LocalContext.current
    val products by app.repo.products.collectAsStateWithLifecycle(emptyList())
    val purchases by app.repo.purchases.collectAsStateWithLifecycle(emptyList())
    val suppliers by app.repo.suppliers.collectAsStateWithLifecycle(emptyList())
    val names = suppliers.associateBy { it.id }
    // every size that is at ZERO stock (only these are ordered, never 1 or 2 pieces), with its brand, category and the supplier of its newest purchase bill
    val allRows = remember(products, purchases) {
        val lastSupplier = HashMap<String, String>()
        purchases.sortedByDescending { it.billDate }.forEach { b ->
            parsePurchaseLines(b.itemsJson).forEach { l ->
                val k = l.article.trim().lowercase()
                if (k.isNotEmpty() && !lastSupplier.containsKey(k)) lastSupplier[k] = b.supplierId
            }
        }
        val rows = ArrayList<OrderRow>()
        products.filter { it.active }.forEach { p ->
            val sid = lastSupplier[p.article.trim().lowercase()] ?: ""
            p.variants.forEach { v ->
                v.sizes.filter { it.qty <= 0 }.forEach { s ->
                    rows.add(OrderRow(sid, p.brand, p.category, p.article.ifBlank { p.name }, v.colour, s.size, s.qty))
                }
            }
        }
        rows
    }
    // pieces the owner chose NOT to order (kept on this phone)
    var notOrder by remember { mutableStateOf(app.repo.prefs.notOrderKeys) }
    // the order pad: pieces the owner has dragged in to order
    var pad by remember { mutableStateOf(padDecode(app.repo.prefs.orderPad)) }
    var padRect by remember { mutableStateOf(Rect.Zero) }
    val padKeys = pad.map { it.key }.toSet()
    val toOrder = allRows.filter { it.key !in notOrder && it.key !in padKeys }
    val skipped = allRows.filter { it.key in notOrder }
    val byBrand = toOrder.groupBy { brandOf(it.brand) }.toSortedMap()

    fun savePad(v: List<PadRow>) {
        pad = v
        app.repo.prefs.orderPad = padEncode(v)
    }
    fun addToPad(r: OrderRow) {
        if (r.key in padKeys) return
        savePad(pad + PadRow(r.key, brandOf(r.brand), r.category.ifBlank { "NO CATEGORY" }, r.article, r.colour, r.size, 1))
    }
    fun skip(r: OrderRow) {
        notOrder = notOrder + r.key
        app.repo.prefs.notOrderKeys = notOrder
    }
    fun unskip(r: OrderRow) {
        notOrder = notOrder - r.key
        app.repo.prefs.notOrderKeys = notOrder
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("सिर्फ़ ज़ीरो स्टॉक वाले साइज़ यहाँ आते हैं (स्टॉक 1 या ज़्यादा वाले नहीं)। ब्रांड → कैटेगरी → आर्टिकल → साइज़। माल को लंबा दबाकर नीचे ऑर्डर पैड में खींचें, या \"पैड में\" दबाएँ। \"नहीं मंगवाना\" से वह साइज़ लिस्ट से हट जाएगा।",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 8.dp)) {
            if (byBrand.isEmpty()) item { EmptyState("कोई भी साइज़ ज़ीरो स्टॉक में नहीं है।") }
            byBrand.forEach { (brand, rows) ->
                item(key = "brand_$brand") {
                    BrandOrderCard(brand, rows,
                        onPad = { addToPad(it) },
                        onDrop = { r, c -> if (padRect.contains(c)) addToPad(r) },
                        onSkip = { skip(it) })
                }
            }
            item {
                Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("नहीं मंगवाने की लिस्ट (${skipped.size})", fontWeight = FontWeight.Bold)
                        Text("यह ऑर्डर में नहीं जाएगी। वापस लाने पर फिर से लिस्ट में आएगी।", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        skipped.forEach { r ->
                            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${brandOf(r.brand)} · ${r.article} · ${r.colour} · size ${r.size}", fontSize = 13.sp, modifier = Modifier.weight(1f))
                                TextButton(onClick = { unskip(r) }) { Text("वापस मंगवाना", fontSize = 12.sp) }
                            }
                        }
                    }
                }
            }
        }
        OrderPadCard(
            pad = pad,
            onRect = { padRect = it },
            onChange = { savePad(it) },
            onSend = { brand, rows ->
                val sid = rows.first().key.substringBefore('|')
                sharePad(ctx, names[sid]?.mobile ?: "", brand, rows)
            })
    }
}

@Composable
private fun BrandOrderCard(brand: String, rows: List<OrderRow>, onPad: (OrderRow) -> Unit,
                           onDrop: (OrderRow, Offset) -> Unit, onSkip: (OrderRow) -> Unit) {
    var open by remember(brand) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                Text(brand, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("${rows.map { it.article }.distinct().size} आर्टिकल · ${rows.size} साइज़", fontSize = 12.sp)
            }
            if (open) {
                rows.groupBy { srLabel(it.category.ifBlank { "NO CATEGORY" }) }.toSortedMap().forEach { (cat, catRows) ->
                    Text(cat, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 10.dp))
                    catRows.groupBy { "${it.article} · ${it.colour}" }.toSortedMap().forEach { (title, aRows) ->
                        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                        aRows.forEach { r ->
                            DraggableRow(r.key, onDrop = { onDrop(r, it) }) {
                                Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("size ${r.size} · stock ${r.qty}", fontSize = 13.sp, modifier = Modifier.weight(1f))
                                    TextButton(onClick = { onPad(r) }) { Text("पैड में", fontSize = 12.sp) }
                                    TextButton(onClick = { onSkip(r) }) { Text("नहीं मंगवाना", fontSize = 12.sp) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A row that can be pressed long and dragged. When it is let go, onDrop gets the row's centre on screen. */
@Composable
private fun DraggableRow(key: String, onDrop: (Offset) -> Unit, content: @Composable () -> Unit) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    var drag by remember { mutableStateOf(Offset.Zero) }
    var dragging by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { origin = it.positionInRoot(); box = it.size }
            .offset { IntOffset(drag.x.roundToInt(), drag.y.roundToInt()) }
            .zIndex(if (dragging) 1f else 0f)
            .background(if (dragging) Color(0xFFFFE0B2) else Color.Transparent)
            .pointerInput(key) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { dragging = true; drag = Offset.Zero },
                    onDrag = { change, amount -> change.consume(); drag += amount },
                    onDragEnd = {
                        dragging = false
                        onDrop(origin + drag + Offset(box.width / 2f, box.height / 2f))
                        drag = Offset.Zero
                    },
                    onDragCancel = { dragging = false; drag = Offset.Zero })
            }
    ) { content() }
}

@Composable
private fun OrderPadCard(pad: List<PadRow>, onRect: (Rect) -> Unit, onChange: (List<PadRow>) -> Unit,
                         onSend: (String, List<PadRow>) -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(top = 6.dp).onGloballyPositioned { onRect(it.boundsInRoot()) },
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))
    ) {
        Column(Modifier.padding(10.dp).heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
            Text("ऑर्डर पैड (${pad.size}) · माल यहाँ खींचकर छोड़ें", fontWeight = FontWeight.Bold)
            if (pad.isEmpty()) Text("अभी खाली है। लिस्ट का माल खींचकर यहाँ छोड़ें।", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            pad.groupBy { it.brand }.toSortedMap().forEach { (brand, rows) ->
                Text(brand, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                rows.forEach { p ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${srLabel(p.category)} · ${p.article} · ${p.colour} · size ${p.size}", fontSize = 12.sp,
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = { onChange(pad.map { if (it.key == p.key) it.copy(qty = (it.qty - 1).coerceAtLeast(1)) else it }) }) { Text("−") }
                        Text("${p.qty}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        TextButton(onClick = { onChange(pad.map { if (it.key == p.key) it.copy(qty = it.qty + 1) else it }) }) { Text("+") }
                        TextButton(onClick = { onChange(pad.filter { it.key != p.key }) }) { Text("हटाओ", fontSize = 12.sp) }
                    }
                }
                Button(onClick = { onSend(brand, rows) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text("$brand का ऑर्डर भेजो")
                }
            }
        }
    }
}

private fun sharePad(ctx: Context, mobile: String, title: String, rows: List<PadRow>) {
    val text = buildString {
        append("Order for ").append(title).append("\n")
        rows.forEach { append("- ${srLabel(it.category)} · ${it.article} · ${it.colour} · size ${it.size} · qty ${it.qty}\n") }
        append("Shivtej Footwear, Akluj")
    }
    val digits = mobile.filter { it.isDigit() }.takeLast(10)
    if (digits.length == 10) {
        openWhatsApp(ctx, digits, text)
    } else {
        val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        ctx.startActivity(Intent.createChooser(share, "Send order").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

// ------------------------------------------------------------------ 3. "मेरा साइज़ आया" list
@Composable
fun SizeRequestsScreen(app: App) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val reqs by app.repo.sizeRequests.collectAsStateWithLifecycle(emptyList())
    val products by app.repo.products.collectAsStateWithLifecycle(emptyList())
    var adding by remember { mutableStateOf(false) }

    fun inStock(r: SizeRequestE): Boolean = products.any { p ->
        p.article.trim().equals(r.article.trim(), ignoreCase = true) &&
            p.variants.any { v -> v.sizes.any { it.size == r.size && it.qty > 0 } }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("Customers waiting for a size. When the size is back in stock the card turns green: tap WhatsApp to tell the customer.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text("Add a waiting customer")
        }
        if (reqs.isEmpty()) EmptyState("No customer is waiting for a size.")
        LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
            items(reqs, key = { it.id }) { r: SizeRequestE ->
                val ok = inStock(r)
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = if (ok) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(12.dp)) {
                        Text((r.name.ifBlank { "Customer" }) + " · " + r.mobile, fontWeight = FontWeight.SemiBold)
                        Text("${r.article} · size ${r.size}" + (if (ok) " · AVAILABLE NOW" else " · not in stock yet"),
                            fontSize = 12.sp, color = if (ok) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (ok) FontWeight.Bold else FontWeight.Normal)
                        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (ok) Button(onClick = {
                                val who = if (r.name.isBlank()) "" else " ${r.name}"
                                openWhatsApp(ctx, r.mobile,
                                    "नमस्ते$who, आपका साइज़ ${r.size} आ गया है (${r.article}). Shivtej Footwear, Akluj")
                            }) { Text("WhatsApp") }
                            TextButton(onClick = { scope.launch { app.repo.deleteSizeRequest(r.id) } }) { Text("Remove") }
                        }
                    }
                }
            }
        }
    }

    if (adding) AddSizeRequestDialog(
        onDismiss = { adding = false },
        onSave = { m, n, a, s ->
            scope.launch { app.repo.addSizeRequest(m, n, a, s) }
            adding = false
        }
    )
}

@Composable
private fun AddSizeRequestDialog(onDismiss: () -> Unit, onSave: (String, String, String, String) -> Unit) {
    var mobile by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var article by remember { mutableStateOf("") }
    var size by remember { mutableStateOf("") }
    val ok = mobile.filter { it.isDigit() }.length >= 10 && article.isNotBlank() && size.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Customer waiting for a size") },
        text = {
            Column {
                OutlinedTextField(value = mobile, onValueChange = { mobile = it }, singleLine = true,
                    label = { Text("Mobile (10 digits)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text("Customer name") }, modifier = Modifier.padding(top = 6.dp))
                OutlinedTextField(value = article, onValueChange = { article = it }, singleLine = true,
                    label = { Text("Article (product code)") }, modifier = Modifier.padding(top = 6.dp))
                OutlinedTextField(value = size, onValueChange = { size = it }, singleLine = true,
                    label = { Text("Size") }, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(mobile, name, article, size) }, enabled = ok) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
