@file:OptIn(ExperimentalMaterial3Api::class)

package com.shivtej.owner.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shivtej.owner.App
import com.shivtej.owner.data.*
import com.shivtej.owner.util.BillMath
import com.shivtej.owner.util.Ledger
import com.shivtej.owner.util.Periods
import com.shivtej.owner.util.Reports
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
private fun cardBorder() = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)

/** Light white dashboard. Every number is calculated from the phone's database (the same data the website sync uses). */
@Composable
fun DashboardScreen(app: App, nav: Nav) {
    val products by app.repo.products.collectAsStateWithLifecycle(emptyList())
    val bills by app.repo.bills.collectAsStateWithLifecycle(emptyList())
    val orders by app.repo.orders.collectAsStateWithLifecycle(emptyList())
    val staff by app.repo.staff.collectAsStateWithLifecycle(emptyList())
    val posCustomers by app.repo.posCustomers.collectAsStateWithLifecycle(emptyList())
    val webCustomers by app.repo.customers.collectAsStateWithLifecycle(emptyList())
    val pays by app.repo.customerPayments.collectAsStateWithLifecycle(emptyList())
    val returnRows by app.repo.returns.collectAsStateWithLifecycle(emptyList())
    val failed by app.repo.failedOps.collectAsStateWithLifecycle(0)
    val st by app.sync.state.collectAsStateWithLifecycle()
    val online by app.net.online.collectAsStateWithLifecycle()

    val low = app.prefs.lowThreshold
    val now = System.currentTimeMillis()
    val start = startOfToday()
    val sizes = products.flatMap { p -> p.variants.flatMap { it.sizes } }
    val lowCount = sizes.count { it.qty in 1..low }
    val outCount = sizes.count { it.qty <= 0 }
    val sales = remember(bills) { bills.toSales() }
    val todayBills = bills.filter { it.createdAt >= start }
    val todaySales = Reports.total(sales, start, Long.MAX_VALUE)
    val todayDiscount = Reports.discount(sales, start, Long.MAX_VALUE)
    val pending = orders.count { it.orderStatus == "New" }
    val activeStaff = staff.count { it.active }
    val customerKeys = remember(posCustomers, webCustomers, bills) {
        (posCustomers.map { BillMathNorm.mobile(it.mobile) } + webCustomers.map { BillMathNorm.mobile(it.mobile) } +
            bills.map { it.customerMobile }).filter { it.length == 10 }.toSet()
    }
    val trend = remember(sales, now / 60_000L) { Reports.dailyTotals(sales, 7, now) }
    val staffToday = remember(sales, now / 60_000L) { Reports.staffStats(sales, start, Long.MAX_VALUE) }
    // customer dues: the same figures as the customer ledgers (see util/Ledger.kt)
    val dues = remember(bills, pays, orders, returnRows, posCustomers) {
        Ledger.summaries(bills, pays, ledgerOrders(orders), ledgerReturns(returnRows), openingsOf(posCustomers))
            .values.filter { it.outstanding > 0.0 }
    }
    val totalDue = BillMath.round2(dues.sumOf { it.outstanding })
    var quickCsv by remember { mutableStateOf(app.prefs.quickActions) }
    var editingQuick by remember { mutableStateOf(false) }
    val productById = products.associateBy { it.id }
    val topToday = remember(todayBills) {
        todayBills.flatMap { parseBillItems(it.itemsJson) }
            .groupBy { it.productId.ifBlank { it.article } }
            .map { (k, v) -> Triple(k, v.sumOf { it.qty }, v.sumOf { it.price * it.qty }) }
            .sortedByDescending { it.second }.take(4)
    }
    val lowAlerts = products.flatMap { p -> p.variants.flatMap { v -> v.sizes.filter { it.qty <= low }.map { Triple(p, v, it) } } }
        .sortedBy { it.third.qty }.take(5)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            if (!online) Banner("You are offline. Everything keeps working and will sync when internet returns.", Color(0xFFFFE0B2))
            st.lastError?.let { if (online) Banner(it, Color(0xFFFFCDD2)) }
            if (st.notice != null) Banner(st.notice!!, Color(0xFFFFF3E0))
            if (failed > 0) Banner("$failed change(s) could not be synced. Open Settings > Sync status.", Color(0xFFFFCDD2))
        }

        // ---------------- quick actions (the owner chooses and orders them with the pencil)
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Quick actions", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { nav.go(Route.ThemeSettings) }) { Text("Theme") }
                IconButton(onClick = { editingQuick = true }) { Icon(Icons.Filled.Edit, "Edit Quick actions") }
            }
        }
        item {
            val actions = quickActionsFrom(quickCsv).map { QuickAction(it.label, it.icon, it.color, it.route) }
            if (actions.isEmpty()) EmptyState("No shortcuts yet. Tap the pencil to add some.")
            else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { a -> QuickTile(a, nav, Modifier.weight(1f)) }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }

        // ---------------- KPI cards (real values)
        item { Text("Overview", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        items(kpiRows(app, products.size, products.count { it.active }, sizes.sumOf { it.qty }, lowCount, outCount,
            todaySales, todayBills.size, orders.size, pending, customerKeys.size, todayDiscount, activeStaff, totalDue, dues.size, nav)) { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { k -> KpiCard(k, nav, Modifier.weight(1f)) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        // ---------------- sales trend
        item {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = cardBorder()) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Sales trend", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("Last 7 days", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("POS sales per day, " + moneyG(app, trend.sumOf { it.sales }) + " in total", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SalesChart(trend.map { it.sales }, Modifier.fillMaxWidth().height(150.dp).padding(top = 10.dp))
                    Row(Modifier.fillMaxWidth()) {
                        trend.forEach { d ->
                            Text(SimpleDateFormat("dd MMM", Locale.ENGLISH).format(Date(d.dayStart)), fontSize = 9.sp,
                                maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        // ---------------- staff sales today + top selling
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Card(
                    onClick = { nav.go(Route.Staff) }, modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = cardBorder()
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Staff sales (today)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        if (staffToday.isEmpty()) Text("No sales today yet.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                        staffToday.take(4).forEach { s ->
                            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                InitialBadge(s.name)
                                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                    Text(s.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    Text("${s.bills} bills", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(moneyG(app, s.sales), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                Card(
                    onClick = { nav.go(Route.Reports) }, modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = cardBorder()
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Top selling (today)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        if (topToday.isEmpty()) Text("Nothing sold today yet.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                        topToday.forEachIndexed { i, (key, qty, rev) ->
                            val p = productById[key]
                            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${i + 1}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = BrandRed, modifier = Modifier.width(16.dp))
                                Thumb(p?.allImages?.firstOrNull(), 32.dp)
                                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                    Text(p?.article ?: "Item", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    Text("$qty pcs", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(moneyG(app, rev), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // ---------------- recent bills
        item {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = cardBorder()) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Recent bills", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { nav.go(Route.Reports) }) { Text("View all") }
                    }
                    if (bills.isEmpty()) Text("No bills yet.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    bills.take(5).forEach { b ->
                        Row(Modifier.fillMaxWidth().clickable { nav.go(Route.Receipt(b.id)) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(b.billNo, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text(b.customerName.ifBlank { "Walk-in Customer" } + (if (b.salesPerson.isNotBlank()) " · " + b.salesPerson else ""),
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            Text(moneyG(app, b.total), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Spacer(Modifier.width(8.dp))
                            Box(Modifier.background(SoftRed, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 3.dp)) {
                                Text(b.method, fontSize = 11.sp, color = BrandRed, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }

        // ---------------- low stock alerts
        item {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = cardBorder()) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Warning, null, tint = BrandRed, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Low stock alerts", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { nav.go(Route.Stock(0, "low")) }) { Text("View all") }
                    }
                    if (lowAlerts.isEmpty()) Text("All good - nothing low.", fontSize = 13.sp, color = GreenOk)
                    lowAlerts.forEach { (p, v, s) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Thumb(p.allImages.firstOrNull(), 36.dp)
                            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(p.article, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text("${v.colour} · Size ${s.size}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Box(Modifier.background(if (s.qty <= 0) Color(0xFFFFEBEE) else Color(0xFFFFF3E0), RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp, vertical = 4.dp)) {
                                Text(if (s.qty <= 0) "Out" else "${s.qty} left", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                    color = if (s.qty <= 0) RedOut else AmberLow)
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }

    if (editingQuick) QuickActionsEditor(quickCsv,
        onSave = { csv -> app.prefs.quickActions = csv; quickCsv = csv; editingQuick = false },
        onCancel = { editingQuick = false })
}

/** Only used to read phone numbers the same way the bill does. */
private object BillMathNorm {
    fun mobile(raw: String): String = com.shivtej.owner.util.BillMath.normMobile(raw)
}

private data class QuickAction(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val color: Color, val route: Route)

@Composable
private fun QuickTile(a: QuickAction, nav: Nav, modifier: Modifier) {
    Card(
        onClick = { nav.go(a.route) }, modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = cardBorder()
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(38.dp).background(a.color.copy(alpha = 0.12f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(a.icon, null, tint = readableTone(a.color), modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.height(6.dp))
            Text(a.label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}

private data class Kpi(val title: String, val value: String, val sub: String, val color: Color, val route: Route)

@Composable
private fun KpiCard(k: Kpi, nav: Nav, modifier: Modifier) {
    Card(
        onClick = { nav.go(k.route) }, modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = cardBorder()
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(k.title, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(k.value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = readableTone(k.color), maxLines = 1)
            Text(k.sub, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}

private fun kpiRows(
    app: App, products: Int, active: Int, stock: Int, low: Int, out: Int,
    todaySales: Double, todayBills: Int, orders: Int, pending: Int, customers: Int, discount: Double,
    staff: Int, dueTotal: Double, dueCount: Int, nav: Nav
): List<List<Kpi>> {
    val rows = listOf(
        listOf(
            Kpi("Total Products", products.toString(), "$active active", Color(0xFF111111), Route.Products("all")),
            Kpi("Total Stock", stock.toString(), "pairs in stock", Color(0xFF2E7D32), Route.Stock(0, "all"))
        ),
        listOf(
            Kpi("Low Stock", low.toString(), "sizes with 1-${app.prefs.lowThreshold} left", AmberLow, Route.Stock(0, "low")),
            Kpi("Out of Stock", out.toString(), "sizes at 0", RedOut, Route.Stock(0, "out"))
        ),
        listOf(
            Kpi("Today's POS Sales", moneyG(app, todaySales), "shop sales today", BrandRed, Route.Reports),
            Kpi("Today's Bills", todayBills.toString(), "bills today", Color(0xFF1565C0), Route.Reports)
        ),
        listOf(
            Kpi("Website Orders", orders.toString(), "all online orders", Color(0xFF283593), Route.Orders("all")),
            Kpi("Pending Orders", pending.toString(), "new, not confirmed", AmberLow, Route.Orders("pending"))
        ),
        listOf(
            Kpi("Total Customers", customers.toString(), "saved customers", Color(0xFF00838F), Route.Customers),
            Kpi("Today's Discount", moneyG(app, discount), "total discount today", GreenOk, Route.Reports)
        ),
        listOf(
            Kpi("Customer dues", moneyG(app, dueTotal), "$dueCount customer(s) owe money", RedOut, Route.Dues),
            Kpi("Staff Members", staff.toString(), "active staff", Color(0xFF6A1B9A), Route.Staff)
        )
    )
    return rows
}

@Composable
private fun SalesChart(values: List<Double>, modifier: Modifier) {
    val max = (values.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        // grid
        for (i in 0..3) {
            val y = h * i / 3f
            drawLine(Color(0xFFEEEEEE), Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }
        if (values.isEmpty()) return@Canvas
        val step = if (values.size > 1) w / (values.size - 1) else 0f
        val pts = values.mapIndexed { i, v -> Offset(i * step, h - (v / max * h * 0.9f).toFloat() - h * 0.05f) }
        val line = Path()
        pts.forEachIndexed { i, p -> if (i == 0) line.moveTo(p.x, p.y) else line.lineTo(p.x, p.y) }
        val area = Path().apply {
            addPath(line)
            lineTo(pts.last().x, h); lineTo(pts.first().x, h); close()
        }
        drawPath(area, Brush.verticalGradient(listOf(Color(0x55E30613), Color(0x05E30613))))
        drawPath(line, BrandRed, style = Stroke(width = 4f, cap = StrokeCap.Round))
        pts.forEach { drawCircle(BrandRed, radius = 5f, center = it) }
    }
}

@Composable
private fun InitialBadge(name: String) {
    Box(Modifier.size(26.dp).background(SoftRed, CircleShape), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase(), color = BrandRed, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}
