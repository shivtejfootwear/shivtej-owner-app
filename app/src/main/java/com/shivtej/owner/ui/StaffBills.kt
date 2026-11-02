@file:OptIn(ExperimentalMaterial3Api::class)

package com.shivtej.owner.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shivtej.owner.App
import com.shivtej.owner.data.Repo
import com.shivtej.owner.net.Api
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Staff Bills: the bills managers sent from the Shivtej Managers app, waiting to be made.
 * Tap "Bill" to open one in POS Billing with its items already in - set the customer and rate, then complete it.
 * When completed it disappears here and the manager's list moves up.
 */
private data class SBill(val id: String, val staffName: String, val items: JSONArray, val at: String, val kind: String = "bill")

private val tFmt = DateTimeFormatter.ofPattern("d MMM, h:mm a")
private fun whenText(iso: String): String = try {
    val t = OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault())
    val mins = (System.currentTimeMillis() - t.toInstant().toEpochMilli()) / 60_000
    when { mins < 1 -> "just now"; mins < 60 -> "$mins min ago"; mins < 24 * 60 -> "${mins / 60} h ago"; else -> t.format(tFmt) }
} catch (e: Exception) { "" }

/** "आज", "कल" or the date, from the bill's time (phone time zone). */
private fun dayOf(iso: String): String = try {
    val d = OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    when (d) { today -> "आज"; today.minusDays(1) -> "कल"; else -> d.format(DateTimeFormatter.ofPattern("d MMM yyyy")) }
} catch (e: Exception) { "" }

/**
 * Closes the managers' bills whose every piece is already sold on a bill here (the owner billed them at the
 * counter), so the same pieces are never billed twice. Returns the ids that were closed. Returns are not touched.
 */
suspend fun closeStaffBillsAlreadySold(app: App, list: JSONArray? = null): Set<String> {
    val a = list ?: (Api.rpc("owner_staff_bills_list", JSONObject().put("p_code", app.prefs.ownerCode)) as? JSONArray ?: JSONArray())
    val closed = HashSet<String>()
    for (i in 0 until a.length()) {
        val o = a.optJSONObject(i) ?: continue
        if (o.optString("kind", "bill") == "return") continue
        val id = o.optString("id")
        if (id.isBlank()) continue
        val sold = app.repo.pendingAlreadyBilled(o.optJSONArray("items") ?: continue) ?: continue
        app.repo.markStaffBillDone(id, sold.billId)
        closed.add(id)
    }
    return closed
}

@Composable
fun StaffBillsScreen(app: App, nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val code = app.prefs.ownerCode
    var bills by remember { mutableStateOf<List<SBill>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf<SBill?>(null) }
    var soldNote by remember { mutableStateOf<String?>(null) }
    // a manager's customer return: the POS return bill is opened for each piece in turn
    var returnQueue by remember { mutableStateOf<List<Repo.SoldInfo>>(emptyList()) }

    fun load(quiet: Boolean = false) {
        if (!quiet) { loading = true; error = null }
        scope.launch {
            try {
                val a = withContext(Dispatchers.IO) { Api.rpc("owner_staff_bills_list", JSONObject().put("p_code", code)) } as? JSONArray ?: JSONArray()
                // a bill whose pieces were already billed at the counter is closed, not shown to be billed again
                val closed = withContext(Dispatchers.IO) { runCatching { closeStaffBillsAlreadySold(app, a) }.getOrDefault(emptySet()) }
                if (closed.isNotEmpty()) toast(ctx, "${closed.size} स्टाफ बिल के पीस पहले ही बिक चुके थे, वे अपने आप बंद हो गए")
                bills = (0 until a.length()).mapNotNull { i ->
                    val o = a.optJSONObject(i) ?: return@mapNotNull null
                    if (o.optString("id") in closed) return@mapNotNull null
                    SBill(o.optString("id"), o.optString("staff_name", "Manager"), o.optJSONArray("items") ?: JSONArray(), o.optString("at"), o.optString("kind", "bill"))
                }
            } catch (e: Exception) {
                val m = e.message.orEmpty()
                if (!quiet) error = when {
                    m.contains("INVALID_OWNER_CODE") -> "Owner code is wrong. Check it in Settings."
                    m.contains("Could not find the function") || m.contains("PGRST202") -> "Run owner_update_17_staff_bills.sql in Supabase first."
                    else -> "Could not load. Check the internet and tap ⟳."
                }
            } finally { loading = false }
        }
    }
    LaunchedEffect(Unit) { load() }
    // keep it fresh while open
    LaunchedEffect(Unit) { while (true) { delay(30_000); load(quiet = true) } }

    fun openInPos(b: SBill) {
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) { app.repo.cartLinesFromPending(b.items) }
                val soldText = r.alreadySold.joinToString("\n") { "• ${it.article} ${it.colour}/${it.size} (बारकोड ${it.code}) → बिल ${it.bill}" }
                if (r.lines.isEmpty()) {
                    if (r.alreadySold.isNotEmpty()) {
                        // already billed: this staff bill is closed so it does not stay in the list
                        withContext(Dispatchers.IO) { app.repo.markStaffBillDone(b.id, r.alreadySold.first().billId) }
                        soldNote = "इस बिल के सारे पीस पहले ही बिक चुके हैं:\n$soldText\n\nयह बिल दोबारा नहीं बनेगा, इसे बंद कर दिया गया है।"
                    }
                    else toast(ctx, "This bill has no items")
                    return@launch
                }
                if (r.alreadySold.isNotEmpty()) android.widget.Toast.makeText(ctx,
                    "पहले से बिके पीस बिल में नहीं जोड़े गए:\n$soldText", android.widget.Toast.LENGTH_LONG).show()
                app.cart.startFromPending(b.id, r.lines)
                nav.go(Route.Pos)
            } catch (e: Exception) { toast(ctx, e.message ?: "Could not open") }
        }
    }

    fun openReturn(b: SBill) {
        val found = (0 until b.items.length()).mapNotNull { b.items.optJSONObject(it) }
            .mapNotNull { app.repo.soldInfo(it.optString("barcode")) }
        if (found.isEmpty()) toast(ctx, "इस बारकोड की POS बिक्री नहीं मिली। बिल नंबर से वापसी बनाएँ, फिर \"हो गया\" दबाएँ।")
        else returnQueue = found
    }

    // the return is made (or not needed): the request leaves the list, and the manager's phone is not affected
    fun markReturnDone(b: SBill) {
        bills = bills?.filterNot { it.id == b.id }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    Api.rpc("owner_staff_bill_done", JSONObject().put("p_code", code).put("p_id", b.id).put("p_pos_bill_id", "return"))
                }
                load(quiet = true)
            } catch (e: Exception) { toast(ctx, "फिर कोशिश करें"); load(quiet = true) }
        }
    }

    fun deleteBill(b: SBill) {
        // gone from the list at once; the server marks it cancelled, so the manager's phone drops it too
        bills = bills?.filterNot { it.id == b.id }
        scope.launch {
            try {
                withContext(Dispatchers.IO) { Api.rpc("owner_staff_bill_delete", JSONObject().put("p_code", code).put("p_id", b.id)) }
                toast(ctx, "बिल हटाया गया")
                load(quiet = true)
            } catch (e: Exception) {
                val m = e.message.orEmpty()
                toast(ctx, if (m.contains("PGRST202") || m.contains("Could not find the function")) "पहले owner_update_28 SQL चलाएँ" else "हटाने में दिक्कत, फिर कोशिश करें")
                load(quiet = true)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("मैनेजर के भेजे बिल। \"बिल बनाएँ\" से POS में खुलेंगे, \"एडिट\" से आइटम बदलें, ग्राहक न लें तो \"डिलीट\" करें।",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            IconButton(onClick = { load() }) { Icon(Icons.Filled.Refresh, "Refresh") }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(12.dp)) }

        val list = bills
        if (list != null) {
            if (list.isEmpty()) EmptyState("No bills from managers right now.")
            val grouped = list.groupBy { it.staffName }
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                grouped.forEach { (staff, items) ->
                    item(key = "h_$staff") {
                        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(42.dp).clip(CircleShape).background(Color(0xFFE65100)), contentAlignment = Alignment.Center) {
                                Text(staff.trim().firstOrNull()?.uppercase() ?: "M", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                            }
                            Column(Modifier.padding(start = 10.dp)) {
                                Text(staff, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Text("${items.size} बिल", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    items.groupBy { dayOf(it.at) }.forEach { (day, dayBills) ->
                        item(key = "d_${staff}_$day") {
                            Text(day, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = BrandRed,
                                modifier = Modifier.padding(top = 6.dp, start = 4.dp))
                        }
                        dayBills.forEachIndexed { idx, b ->
                            item(key = b.id) {
                                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Column(Modifier.padding(10.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(Modifier.size(26.dp).clip(CircleShape).background(Color(0xFF283593)), contentAlignment = Alignment.Center) {
                                                Text("${idx + 1}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                            }
                                            val n = (0 until b.items.length()).sumOf { b.items.optJSONObject(it)?.optInt("qty", 0) ?: 0 }
                                            Text("  $n पीस · ${whenText(b.at)}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                        }
                                        for (k in 0 until b.items.length()) {
                                            val it = b.items.optJSONObject(k) ?: continue
                                            Text("${it.optString("article")} · ${it.optString("colour")} · साइज़ ${it.optString("size")} × ${it.optInt("qty", 1)}",
                                                fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp, top = 2.dp))
                                        }
                                        if (b.kind == "return") Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text("वापसी", fontWeight = FontWeight.Bold, color = BrandRed, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                            Button(onClick = { openReturn(b) }, colors = ButtonDefaults.buttonColors(containerColor = BrandRed)) { Text("वापसी बनाएँ", fontSize = 12.sp) }
                                            OutlinedButton(onClick = { markReturnDone(b) }) { Text("हो गया", fontSize = 12.sp) }
                                        } else Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Button(onClick = { openInPos(b) }, colors = ButtonDefaults.buttonColors(containerColor = BrandRed), modifier = Modifier.weight(1f)) { Text("बिल बनाएँ", fontSize = 12.sp) }
                                            OutlinedButton(onClick = { openInPos(b) }, modifier = Modifier.weight(1f)) { Text("एडिट", fontSize = 12.sp) }
                                            OutlinedButton(onClick = { askDelete = b }, modifier = Modifier.weight(1f)) {
                                                Text("डिलीट", fontSize = 12.sp, color = BrandRed)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else if (error == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
    }

    returnQueue.firstOrNull()?.let { sold ->
        ReturnBillDialog(app, sold, onClose = { returnQueue = returnQueue.drop(1); load(quiet = true) })
    }

    soldNote?.let { t ->
        AlertDialog(
            onDismissRequest = { soldNote = null },
            title = { Text("पीस पहले ही बिक चुके हैं") },
            text = { Text(t) },
            confirmButton = { TextButton(onClick = { soldNote = null; load(quiet = true) }) { Text("ठीक है") } })
    }

    askDelete?.let { b ->
        AlertDialog(
            onDismissRequest = { askDelete = null },
            title = { Text("यह बिल हटाएँ?") },
            text = { Text("${b.staffName} का यह बिल यहाँ से और उनके फ़ोन की सूची से भी हट जाएगा। ग्राहक न लेने पर ही हटाएँ।") },
            confirmButton = { TextButton(onClick = { askDelete = null; deleteBill(b) }) { Text("हाँ, हटाएँ", color = BrandRed) } },
            dismissButton = { TextButton(onClick = { askDelete = null }) { Text("रद्द") } })
    }
}
