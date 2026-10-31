package com.shivtej.owner.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shivtej.owner.App
import com.shivtej.owner.data.ReturnE
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private class ShopReturnLine(val article: String, val colour: String, val size: String, val qty: Int, val price: Double)

private fun shopReturnLines(json: String): List<ShopReturnLine> = runCatching {
    val a = JSONArray(json)
    (0 until a.length()).map { i ->
        val o = a.getJSONObject(i)
        ShopReturnLine(o.optString("article"), o.optString("colour"), o.optString("size"), o.optInt("qty", 1), o.optDouble("price", 0.0))
    }
}.getOrDefault(emptyList())

/** Start of today (monthOnly = false) or of this month (monthOnly = true), in milliseconds. */
private fun periodStart(monthOnly: Boolean): Long = Calendar.getInstance().apply {
    if (monthOnly) set(Calendar.DAY_OF_MONTH, 1)
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** All returns made at the shop counter (POS bills), with the amount given back and the stock status. */
@Composable
fun ReturnsScreen(app: App, nav: Nav) {
    val all by produceState(initialValue = emptyList<ReturnE>()) { value = app.repo.returnsAll() }
    var period by remember { mutableStateOf("today") }
    val fmt = remember { SimpleDateFormat("dd MMM yyyy · hh:mm a", Locale.ENGLISH) }
    val from = when (period) { "today" -> periodStart(false); "month" -> periodStart(true); else -> 0L }
    val list = all.filter { it.createdAt >= from }.sortedByDescending { it.createdAt }
    val restockedPieces = list.filter { it.restock }.sumOf { r -> shopReturnLines(r.itemsInJson).sumOf { it.qty } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("today" to "आज", "month" to "इस महीने", "all" to "सब").forEach { (k, label) ->
                FilterChip(selected = period == k, onClick = { period = k }, label = { Text(label) })
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("सार", fontWeight = FontWeight.Bold)
                Text("वापसी बिल: ${list.size}")
                Text("वापस की गई रकम: ${money(app, list.sumOf { it.refund })}")
                Text("स्टॉक में जुड़े पीस: $restockedPieces")
            }
        }
        if (list.isEmpty()) {
            Text("इस समय में कोई वापसी नहीं।", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        list.forEach { r ->
            val lines = shopReturnLines(r.itemsInJson)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (r.kind == "EXCHANGE") "एक्सचेंज" else "वापसी", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(money(app, r.refund), fontWeight = FontWeight.Bold)
                    }
                    Text(fmt.format(Date(r.createdAt)), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(r.reason, fontSize = 13.sp)
                    lines.forEach { Text("${it.article} · ${it.colour} · साइज़ ${it.size} × ${it.qty}", fontSize = 13.sp) }
                    Text(if (r.restock) "✓ स्टॉक में जुड़ा" else "स्टॉक में नहीं जुड़ा (खराब माल)",
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        color = if (r.restock) GreenOk else Color(0xFFE65100))
                    if (r.status == "REJECTED") Text("सर्वर ने यह वापसी नहीं मानी", fontSize = 12.sp, color = Color(0xFFC62828))
                }
            }
        }
    }
}
