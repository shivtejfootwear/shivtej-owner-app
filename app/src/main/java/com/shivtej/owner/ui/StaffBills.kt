@file:OptIn(ExperimentalMaterial3Api::class)

package com.shivtej.owner.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
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
import com.shivtej.owner.net.Api
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Staff Bills: the bills managers sent from the Shivtej Managers app, waiting to be made.
 * Tap "Bill" to open one in POS Billing with its items already in - set the customer and rate, then complete it.
 * When completed it disappears here and the manager's list moves up.
 */
private data class SBill(val id: String, val staffName: String, val items: JSONArray, val at: String)

private val tFmt = DateTimeFormatter.ofPattern("d MMM, h:mm a")
private fun whenText(iso: String): String = try {
    val t = OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault())
    val mins = (System.currentTimeMillis() - t.toInstant().toEpochMilli()) / 60_000
    when { mins < 1 -> "just now"; mins < 60 -> "$mins min ago"; mins < 24 * 60 -> "${mins / 60} h ago"; else -> t.format(tFmt) }
} catch (e: Exception) { "" }

@Composable
fun StaffBillsScreen(app: App, nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val code = app.prefs.ownerCode
    var bills by remember { mutableStateOf<List<SBill>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    fun load(quiet: Boolean = false) {
        if (!quiet) { loading = true; error = null }
        scope.launch {
            try {
                val a = withContext(Dispatchers.IO) { Api.rpc("owner_staff_bills_list", JSONObject().put("p_code", code)) } as? JSONArray ?: JSONArray()
                bills = (0 until a.length()).mapNotNull { i ->
                    val o = a.optJSONObject(i) ?: return@mapNotNull null
                    SBill(o.optString("id"), o.optString("staff_name", "Manager"), o.optJSONArray("items") ?: JSONArray(), o.optString("at"))
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
                val lines = withContext(Dispatchers.IO) { app.repo.cartLinesFromPending(b.items) }
                if (lines.isEmpty()) { toast(ctx, "This bill has no items"); return@launch }
                app.cart.startFromPending(b.id, lines)
                nav.go(Route.Pos)
            } catch (e: Exception) { toast(ctx, e.message ?: "Could not open") }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Bills sent by managers. Tap \"Bill\" to set the customer and rate.", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            IconButton(onClick = { load() }) { Icon(Icons.Filled.Refresh, "Refresh") }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(12.dp)) }

        val list = bills
        if (list != null) {
            if (list.isEmpty()) EmptyState("No bills from managers right now.")
            // group by manager, keep server order (name, then oldest first)
            val grouped = list.groupBy { it.staffName }
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                grouped.forEach { (staff, items) ->
                    item(key = "h_$staff") {
                        Text("🪪 $staff · ${items.size} bill(s)", fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    }
                    items.forEachIndexed { idx, b ->
                      item(key = b.id) {
                        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(28.dp).clip(CircleShape).background(Color(0xFF283593)), contentAlignment = Alignment.Center) {
                                    Text("${idx + 1}", color = Color.White, fontWeight = FontWeight.Bold)
                                }
                                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                    val n = (0 until b.items.length()).sumOf { b.items.optJSONObject(it)?.optInt("qty", 0) ?: 0 }
                                    Text("$n item(s) · rate to set", fontWeight = FontWeight.SemiBold)
                                    val names = (0 until b.items.length()).mapNotNull { b.items.optJSONObject(it)?.optString("article")?.ifBlank { b.items.optJSONObject(it)?.optString("name") } }
                                    Text((names.joinToString(", ").ifBlank { "items" }) + " · " + whenText(b.at),
                                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Button(onClick = { openInPos(b) }, colors = ButtonDefaults.buttonColors(containerColor = BrandRed)) { Text("Bill") }
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
}
