@file:OptIn(ExperimentalMaterial3Api::class)

package com.shivtej.owner.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shivtej.owner.App
import com.shivtej.owner.data.*
import com.shivtej.owner.util.Periods
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

enum class TrendRange(val label: String) { DAY("Day"), WEEK("Week"), MONTH("Month"), YEAR("Year") }

/** One bar: a day, a week, a month or a year, with its POS bills. */
data class TrendBucket(val label: String, val start: Long, val end: Long, val total: Double, val bills: List<BillE>)

/**
 * Buckets for a range, oldest first, ending with the current period.
 * Day = last 7 days, Week = last 8 weeks (Monday start), Month = last 12 months, Year = every year since the first bill.
 */
fun trendBuckets(bills: List<BillE>, range: TrendRange, now: Long): List<TrendBucket> {
    val (field, step, count) = when (range) {
        TrendRange.DAY -> Triple(Calendar.DAY_OF_MONTH, 1, 7)
        TrendRange.WEEK -> Triple(Calendar.DAY_OF_MONTH, 7, 8)
        TrendRange.MONTH -> Triple(Calendar.MONTH, 1, 12)
        TrendRange.YEAR -> {
            val firstYear = Calendar.getInstance().apply { timeInMillis = bills.minOfOrNull { it.createdAt } ?: now }.get(Calendar.YEAR)
            val thisYear = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.YEAR)
            Triple(Calendar.YEAR, 1, (thisYear - firstYear + 1).coerceIn(1, 10))
        }
    }
    // start of the current period
    val currentStart = Calendar.getInstance().apply {
        timeInMillis = now
        firstDayOfWeek = Calendar.MONDAY
        when (range) {
            TrendRange.DAY -> return@apply
            TrendRange.WEEK -> set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            TrendRange.MONTH -> set(Calendar.DAY_OF_MONTH, 1)
            TrendRange.YEAR -> { set(Calendar.DAY_OF_YEAR, 1) }
        }
    }.let { Periods.startOfDay(it.timeInMillis) }
    val fmt = SimpleDateFormat(when (range) {
        TrendRange.MONTH -> "MMM yy"
        TrendRange.YEAR -> "yyyy"
        else -> "dd MMM"
    }, Locale.ENGLISH)
    return (count - 1 downTo 0).map { back ->
        val start = Calendar.getInstance().apply { timeInMillis = currentStart; add(field, -back * step) }.timeInMillis
        val next = Calendar.getInstance().apply { timeInMillis = start; add(field, step) }.timeInMillis
        val end = next - 1
        val inside = bills.filter { it.createdAt in start..end }
        TrendBucket(fmt.format(java.util.Date(start)), start, end, inside.sumOf { it.total }, inside)
    }
}

// ================================================================== SALES TREND (detail, opened from the dashboard card)
@Composable
fun SalesTrendScreen(app: App, nav: Nav) {
    val bills by app.repo.bills.collectAsStateWithLifecycle(emptyList())
    var range by remember { mutableStateOf(TrendRange.DAY) }
    var open by remember(range) { mutableStateOf(-1) }
    val now = System.currentTimeMillis()
    val buckets = remember(bills, range, now / 60_000L) { trendBuckets(bills, range, now) }
    val total = buckets.sumOf { it.total }
    val billCount = buckets.sumOf { it.bills.size }
    val avg = if (billCount == 0) 0.0 else total / billCount
    val best = buckets.maxByOrNull { it.total }
    val last = buckets.lastOrNull()?.total ?: 0.0
    val prev = if (buckets.size >= 2) buckets[buckets.size - 2].total else 0.0
    val change = if (prev <= 0.0) null else ((last - prev) / prev * 100).toInt()
    val billFmt = remember { SimpleDateFormat("hh:mm a", Locale.ENGLISH) }

    Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("POS sales. Tap a bar or a row to see its bills. Tap a bill to open it.", fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TrendRange.values().forEach { r ->
                FilterChip(selected = range == r, onClick = { range = r }, label = { Text(r.label) })
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            KpiMini("Total", moneyG(app, total), Modifier.weight(1f))
            KpiMini("Bills", billCount.toString(), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            KpiMini("Average bill", moneyG(app, avg), Modifier.weight(1f))
            KpiMini("Best " + range.label.lowercase(), best?.let { it.label + " · " + moneyG(app, it.total) } ?: "-", Modifier.weight(1f))
        }
        Text(
            when {
                change == null -> "Latest ${range.label.lowercase()} compared with the one before: no earlier sales."
                change >= 0 -> "Latest ${range.label.lowercase()} is up $change% compared with the one before."
                else -> "Latest ${range.label.lowercase()} is down ${-change}% compared with the one before."
            }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        val maxTotal = (buckets.maxOfOrNull { it.total } ?: 0.0).coerceAtLeast(1.0)
        Canvas(Modifier.fillMaxWidth().height(170.dp).pointerInput(buckets) {
            detectTapGestures { p ->
                val slot = size.width / buckets.size.coerceAtLeast(1)
                val i = (p.x / slot).toInt()
                if (i in buckets.indices) open = if (open == i) -1 else i
            }
        }) {
            val n = buckets.size.coerceAtLeast(1)
            val slot = size.width / n
            val barW = slot * 0.6f
            buckets.forEachIndexed { i, b ->
                val h = (b.total / maxTotal * size.height).toFloat()
                drawRect(
                    color = if (i == open) Color(0xFF7F0000) else if (i == buckets.lastIndex) BrandRed else BrandRed.copy(alpha = 0.45f),
                    topLeft = Offset(i * slot + (slot - barW) / 2, size.height - h),
                    size = Size(barW, h)
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            buckets.forEach { b ->
                Text(b.label, fontSize = 9.sp, maxLines = 1, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // every period, newest first; a tapped one shows its bills
        buckets.indices.reversed().forEach { i ->
            val b = buckets[i]
            Card(Modifier.fillMaxWidth().clickable { open = if (open == i) -1 else i }) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(b.label, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text("${moneyG(app, b.total)} · ${b.bills.size} bills", fontWeight = FontWeight.Bold, color = BrandRed)
                    }
                    if (open == i) {
                        if (b.bills.isEmpty()) Text("No POS bills in this period.", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                        b.bills.sortedByDescending { it.createdAt }.forEach { bill ->
                            Row(Modifier.fillMaxWidth().clickable { nav.go(Route.Receipt(bill.id)) }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(bill.billNo.ifBlank { "Bill" }, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Text(billFmt.format(java.util.Date(bill.createdAt)) +
                                        (if (bill.customerName.isNotBlank()) " · " + bill.customerName else ""),
                                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(moneyG(app, bill.total), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KpiMini(title: String, value: String, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(10.dp)) {
            Text(title, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}
