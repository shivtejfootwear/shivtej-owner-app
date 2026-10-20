package com.shivtej.owner.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
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

/** A piece that is low on stock, with the supplier it was last bought from ("" = no purchase bill has it). */
private data class OrderRow(val supplierId: String, val article: String, val colour: String, val size: String, val qty: Int)

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

// ------------------------------------------------------------------ 2. supplier order list
@Composable
fun SupplierOrderScreen(app: App) {
    val ctx = LocalContext.current
    val products by app.repo.products.collectAsStateWithLifecycle(emptyList())
    val purchases by app.repo.purchases.collectAsStateWithLifecycle(emptyList())
    val suppliers by app.repo.suppliers.collectAsStateWithLifecycle(emptyList())
    val names = suppliers.associateBy { it.id }
    val groups = remember(products, purchases) {
        // the supplier of an article = the supplier on its newest purchase bill
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
                v.sizes.filter { it.qty <= LOW_STOCK_LIMIT }.forEach { s ->
                    rows.add(OrderRow(sid, p.article.ifBlank { p.name }, v.colour, s.size, s.qty))
                }
            }
        }
        rows.groupBy { it.supplierId }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text("Pieces with $LOW_STOCK_LIMIT or less left, grouped by the supplier of their newest purchase bill. Send each list to the supplier in one tap.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (groups.isEmpty()) EmptyState("No item is low on stock right now.")
        groups.toList().sortedBy { (sid, _) -> names[sid]?.name ?: "~" }.forEach { (sid, list) ->
            val sup = names[sid]
            val title = sup?.name ?: "Supplier not set (no purchase bill has these items)"
            Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(title, fontWeight = FontWeight.Bold)
                    if (sup != null && sup.mobile.isNotBlank()) Text("Mobile: " + sup.mobile, fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    list.forEach { r ->
                        Text("${r.article} · ${r.colour} · size ${r.size} · left ${r.qty}", fontSize = 13.sp,
                            modifier = Modifier.padding(top = 3.dp))
                    }
                    Button(onClick = { shareOrder(ctx, sup?.mobile ?: "", title, list) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Send order list") }
                }
            }
        }
    }
}

private fun shareOrder(ctx: Context, mobile: String, title: String, rows: List<OrderRow>) {
    val text = buildString {
        append("Order list for ").append(title).append("\n")
        rows.forEach { append("- ${it.article} · ${it.colour} · size ${it.size} (left ${it.qty})\n") }
        append("Shivtej Footwear, Akluj")
    }
    val digits = mobile.filter { it.isDigit() }.takeLast(10)
    if (digits.length == 10) {
        openWhatsApp(ctx, digits, text)
    } else {
        val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        ctx.startActivity(Intent.createChooser(share, "Send order list").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
