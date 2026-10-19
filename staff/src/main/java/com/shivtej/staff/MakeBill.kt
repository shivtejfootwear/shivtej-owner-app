@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.shivtej.staff

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** One line in a manager's bill (no rate - the owner sets that). */
private data class MBItem(
    val productId: String, val article: String, val name: String, val colour: String, val size: String,
    val barcode: String, val qty: Int, val mrp: Double
)

private fun MBItem.key() = "$productId|$colour|$size"
private fun MBItem.json() = JSONObject().put("productId", productId).put("article", article).put("name", name)
    .put("colour", colour).put("size", size).put("barcode", barcode).put("qty", qty).put("mrp", mrp)

private data class PendingBill(val id: String, val items: List<MBItem>, val at: String)

private fun parseItems(a: JSONArray?): List<MBItem> {
    if (a == null) return emptyList()
    return (0 until a.length()).mapNotNull { i ->
        val o = a.optJSONObject(i) ?: return@mapNotNull null
        MBItem(o.optString("productId"), o.optString("article"), o.optString("name"), o.optString("colour"),
            o.optString("size"), o.optString("barcode"), o.optInt("qty", 1), o.optDouble("mrp", 0.0))
    }
}

private fun toast(c: android.content.Context, m: String) = Toast.makeText(c, m, Toast.LENGTH_SHORT).show()

@Composable
fun MakeBillScreen(store: Store, onBack: () -> Unit, onClosed: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val kb = LocalSoftwareKeyboardController.current
    val cart = remember { mutableStateListOf<MBItem>() }
    var editingId by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var products by remember { mutableStateOf<List<SProduct>>(emptyList()) }
    var pending by remember { mutableStateOf<List<PendingBill>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<SProduct?>(null) }

    fun refresh() {
        val t = store.token ?: return onClosed()
        scope.launch {
            try {
                val (cat, pend) = withContext(Dispatchers.IO) {
                    parseCatalog(Api.catalog(t, store.deviceId)) to Api.billsList(t, store.deviceId)
                }
                products = cat
                pending = (0 until pend.length()).mapNotNull { i ->
                    val o = pend.optJSONObject(i) ?: return@mapNotNull null
                    PendingBill(o.optString("id"), parseItems(o.optJSONArray("items")), o.optString("at"))
                }
            } catch (e: NotAllowed) { onClosed() } catch (_: Exception) { }
        }
    }
    LaunchedEffect(Unit) { refresh() }

    fun addItem(it: MBItem) {
        val i = cart.indexOfFirst { c -> c.key() == it.key() }
        if (i >= 0) cart[i] = cart[i].copy(qty = cart[i].qty + it.qty) else cart.add(it)
    }

    fun onQuery(v: String) {
        query = v
        val t = v.trim()
        // a scanned / typed barcode (digits, 6+): resolve it on the server and add
        if (t.length >= 6 && t.none { it.isWhitespace() } && t.any { it.isDigit() }) {
            val tok = store.token ?: return
            scope.launch {
                try {
                    val r = withContext(Dispatchers.IO) { Api.lookup(tok, store.deviceId, t) }
                    if (r.optBoolean("found")) {
                        addItem(MBItem(r.optString("productId"), r.optString("article"), r.optString("name"),
                            r.optString("colour"), r.optString("size"), r.optString("barcode"), 1, r.optDouble("mrp", 0.0)))
                        query = ""; toast(ctx, "Added: ${r.optString("article")} ${r.optString("size")}")
                    }
                } catch (e: NotAllowed) { onClosed() } catch (_: Exception) { }
            }
        }
    }

    fun send() {
        if (cart.isEmpty()) { toast(ctx, "Add at least one item"); return }
        val t = store.token ?: return onClosed()
        busy = true
        val items = JSONArray().apply { cart.forEach { put(it.json()) } }
        val ed = editingId
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (ed == null) Api.billSend(t, store.deviceId, items) else Api.billUpdate(t, store.deviceId, ed, items)
                }
                toast(ctx, if (ed == null) "Bill sent to owner" else "Bill updated")
                cart.clear(); editingId = null; query = ""
                refresh()
            } catch (e: NotAllowed) { onClosed() }
            catch (e: Exception) { toast(ctx, "Could not send. Check internet.") }
            finally { busy = false }
        }
    }

    val results = if (query.isBlank()) emptyList() else {
        val q = query.trim().lowercase()
        products.filter { p -> listOf(p.article, p.brand, p.name, p.type).any { it.lowercase().contains(q) } }.take(20)
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") } },
            title = { Text(if (editingId != null) "Edit Bill" else "Make Bill", fontWeight = FontWeight.ExtraBold) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = StaffBlue, titleContentColor = Color.White,
                navigationIconContentColor = Color.White)
        )
        OutlinedTextField(
            query, { onQuery(it) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            placeholder = { Text("Search article / brand, or scan barcode") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Clear") } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { kb?.hide() })
        )

        LazyColumn(Modifier.weight(1f).padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (results.isNotEmpty()) item {
                Card {
                    Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                        results.forEach { p ->
                            Row(Modifier.fillMaxWidth().clickable { picker = p }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(p.title, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    Text("${p.brand} · MRP ${money(p.mrp)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(Icons.Filled.Add, "Add", tint = StaffBlue)
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }

            item { Text("Items in this bill (${cart.size})", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp)) }
            if (cart.isEmpty()) item {
                Text("Search or scan a product to add it. The owner will set the rate and make the final bill.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(cart, key = { it.key() }) { it ->
                val i = cart.indexOf(it)
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(it.name.ifBlank { it.article }, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${it.article} · ${it.colour} / ${it.size} · MRP ${money(it.mrp)}", fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { if (i >= 0) cart[i] = it.copy(qty = (it.qty - 1).coerceAtLeast(1)) }) { Icon(Icons.Filled.Remove, "Less") }
                        Text("${it.qty}", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        IconButton(onClick = { if (i >= 0) cart[i] = it.copy(qty = it.qty + 1) }) { Icon(Icons.Filled.Add, "More") }
                        IconButton(onClick = { if (i >= 0) cart.removeAt(i) }) { Icon(Icons.Filled.Delete, "Remove", tint = BrandRed) }
                    }
                }
            }

            // pending bills already sent (numbered 1,2,3...), oldest first
            if (pending.isNotEmpty()) {
                item { Text("Sent to owner (waiting) · ${pending.size}", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp)) }
                itemsIndexed(pending) { idx, b ->
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFF2F6FC))) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(28.dp).clip(CircleShape).background(StaffBlue), contentAlignment = Alignment.Center) {
                                Text("${idx + 1}", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text("${b.items.sumOf { it.qty }} item(s)", fontWeight = FontWeight.SemiBold)
                                Text(b.items.joinToString(", ") { it.article.ifBlank { it.name } }.take(40), fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            TextButton(onClick = { cart.clear(); cart.addAll(b.items); editingId = b.id }) { Text("Edit") }
                            IconButton(onClick = {
                                val t = store.token ?: return@IconButton
                                scope.launch {
                                    try { withContext(Dispatchers.IO) { Api.billDelete(t, store.deviceId, b.id) }; refresh() }
                                    catch (e: NotAllowed) { onClosed() } catch (_: Exception) { }
                                }
                            }) { Icon(Icons.Filled.Delete, "Cancel", tint = BrandRed) }
                        }
                    }
                }
                item { Text("The owner sets the rate and completes these. A completed bill disappears from here.",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)) }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }

        HorizontalDivider()
        Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (editingId != null) OutlinedButton(onClick = { cart.clear(); editingId = null; query = "" },
                modifier = Modifier.weight(1f).height(52.dp)) { Text("Cancel edit") }
            Button(onClick = { send() }, enabled = cart.isNotEmpty() && !busy,
                colors = ButtonDefaults.buttonColors(containerColor = StaffBlue),
                modifier = Modifier.weight(2f).height(52.dp)) {
                Text(if (busy) "Please wait..." else if (editingId != null) "Update bill (${cart.size})" else "Send bill to owner (${cart.size})",
                    fontWeight = FontWeight.Bold)
            }
        }
    }

    // pick a colour + size for a product tapped from the search results
    picker?.let { p ->
        var colour by remember(p.id) { mutableStateOf(p.colours.firstOrNull { c -> c.sizes.any { it.inStock } } ?: p.colours.firstOrNull()) }
        AlertDialog(
            onDismissRequest = { picker = null },
            title = { Text(p.title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (p.colours.size > 1) {
                        Text("Colour", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            p.colours.forEach { c ->
                                FilterChip(selected = colour?.colour == c.colour, onClick = { colour = c }, label = { Text(c.colour.ifBlank { "-" }) })
                            }
                        }
                    }
                    Text("Size (tap to add)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        (colour?.sizes ?: emptyList()).forEach { s ->
                            val c = colour
                            AssistChip(
                                enabled = s.inStock, onClick = {
                                    if (c != null) {
                                        addItem(MBItem(p.id, p.article, p.title, c.colour, s.size, "", 1, p.mrp))
                                        toast(ctx, "Added: ${p.article} ${s.size}"); picker = null; query = ""
                                    }
                                },
                                label = { Text(if (s.inStock) s.size else "${s.size} (0)") }
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picker = null }) { Text("Close") } }
        )
    }
}
