@file:OptIn(ExperimentalMaterial3Api::class)

package com.shivtej.owner.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import com.shivtej.owner.data.*
import com.shivtej.owner.util.BillMath
import com.shivtej.owner.util.Fmt
import com.shivtej.owner.util.Periods
import com.shivtej.owner.util.Reports
import com.shivtej.owner.util.StaffStat
import kotlinx.coroutines.launch

// ================================================================== STAFF (sales people)
@Composable
fun StaffScreen(app: App, nav: Nav) {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Staff") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Sales report") })
        }
        if (tab == 0) StaffList(app) else StaffSalesReport(app, nav)
    }
}

@Composable
private fun StaffList(app: App) {
    val staff by app.repo.staff.collectAsStateWithLifecycle(emptyList())
    var editing by remember { mutableStateOf<StaffE?>(null) }
    var adding by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            Text(
                "People who make sales. Someone who leaves is switched off (Active off), never deleted, so their past sales stay in the reports.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp)
            )
            if (staff.isEmpty()) EmptyState("No staff yet. Tap + to add the first sales person.")
            LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp)) {
                items(staff, key = { it.id }) { s ->
                    Row(Modifier.fillMaxWidth().clickable { editing = s }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(42.dp).background(if (s.active) SoftRed else SoftGrey, CircleShape), contentAlignment = Alignment.Center) {
                            Text(s.name.take(1).uppercase(), color = if (s.active) BrandRed else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                        }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(s.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(s.mobile.ifBlank { "No mobile number" }, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(if (s.active) "Active" else "Inactive", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            color = if (s.active) GreenOk else RedOut)
                    }
                    HorizontalDivider()
                }
            }
        }
        FloatingActionButton(
            onClick = { adding = true }, containerColor = BrandRed, contentColor = Color.White,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) { Icon(Icons.Filled.Add, "Add staff") }
    }
    if (adding) StaffDialog(app, null) { adding = false }
    editing?.let { s -> StaffDialog(app, s) { editing = null } }
}

@Composable
private fun StaffDialog(app: App, s: StaffE?, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(s?.name ?: "") }
    var mobile by remember { mutableStateOf(s?.mobile ?: "") }
    var active by remember { mutableStateOf(s?.active ?: true) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(if (s == null) "Add staff" else "Edit staff") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    mobile, { mobile = it.filter(Char::isDigit).take(10) }, label = { Text("Mobile (optional)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                )
                if (s != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Active (can be picked for bills)", modifier = Modifier.weight(1f))
                    Switch(checked = active, onCheckedChange = { active = it })
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && !busy, onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        app.repo.saveStaff(s?.id, name, mobile, active)
                        onDone()
                    } catch (e: Exception) {
                        error = e.message ?: "Could not save"
                    } finally { busy = false }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } }
    )
}

/** Period choice shared by the staff report. Returns (from, to) in milliseconds. */
@Composable
private fun StaffSalesReport(app: App, nav: Nav) {
    val bills by app.repo.bills.collectAsStateWithLifecycle(emptyList())
    val staff by app.repo.staff.collectAsStateWithLifecycle(emptyList())
    var period by remember { mutableStateOf("today") }
    var fromText by remember { mutableStateOf("") }
    var toText by remember { mutableStateOf("") }
    val now = System.currentTimeMillis()

    val fromCustom = Periods.parseDay(fromText)
    val toCustom = Periods.parseDay(toText)
    val customBad = period == "custom" && (fromCustom == null || toCustom == null || fromCustom > toCustom)
    val (from, to) = when (period) {
        "today" -> startOfToday() to Periods.endOfDay(now)
        "month" -> Periods.startOfMonth(now) to Periods.endOfDay(now)
        "7" -> Periods.daysAgoStart(now, 6) to Periods.endOfDay(now)
        "all" -> 0L to Long.MAX_VALUE
        else -> (fromCustom ?: 0L) to (toCustom?.let { Periods.endOfDay(it) } ?: Long.MAX_VALUE)
    }
    val sales = remember(bills) { bills.toSales() }
    val stats = Reports.staffStats(sales, from, to)
    val totalSales = Reports.total(sales, from, to)
    val totalBills = Reports.count(sales, from, to)
    val totalItems = stats.sumOf { it.items }
    val totalDisc = Reports.discount(sales, from, to)
    // active staff with no sale in this period still appear (with 0)
    val rows = stats.toMutableList()
    staff.filter { s -> s.active && rows.none { it.personId == s.id } }
        .forEach { rows.add(StaffStat(it.id, it.name, 0, 0.0, 0, 0.0)) }
    val sorted = rows.sortedByDescending { it.sales }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("today" to "Today", "month" to "This month", "7" to "Last 7 days", "all" to "All time", "custom" to "Date range")
                    .forEach { (k, l) -> Chip(l, period == k) { period = k } }
            }
        }
        if (period == "custom") item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(fromText, { fromText = it.take(10) }, label = { Text("From dd-mm-yyyy") }, singleLine = true,
                    isError = fromText.isNotBlank() && fromCustom == null, modifier = Modifier.weight(1f))
                OutlinedTextField(toText, { toText = it.take(10) }, label = { Text("To dd-mm-yyyy") }, singleLine = true,
                    isError = toText.isNotBlank() && toCustom == null, modifier = Modifier.weight(1f))
            }
            if (customBad) Text("Type both dates, for example 01-10-2026, and the first date must not be after the second.",
                fontSize = 12.sp, color = RedOut, modifier = Modifier.padding(top = 4.dp))
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                ReportMini("Total bills", totalBills.toString(), Modifier.weight(1f))
                ReportMini("Total sales", moneyG(app, totalSales), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)) {
                ReportMini("Items sold", totalItems.toString(), Modifier.weight(1f))
                ReportMini("Discount given", moneyG(app, totalDisc), Modifier.weight(1f))
            }
            Text("Tap a person to see every bill they made.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (customBad) item { EmptyState("Choose a valid date range.") }
        else items(sorted, key = { it.personId.ifBlank { "none" } }) { s ->
            val clickable = s.personId.isNotBlank()
            Row(
                Modifier.fillMaxWidth().then(if (clickable) Modifier.clickable { nav.go(Route.StaffDetail(s.personId)) } else Modifier)
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(40.dp).background(SoftRed, CircleShape), contentAlignment = Alignment.Center) {
                    Text(s.name.take(1).uppercase(), color = BrandRed, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(s.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text("${s.bills} bills · ${s.items} items" + if (s.discount > 0) " · discount ${moneyG(app, s.discount)}" else "",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(moneyG(app, s.sales), fontWeight = FontWeight.Bold)
                    if (clickable) Text("history >", fontSize = 11.sp, color = BrandRed)
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun ReportMini(title: String, value: String, modifier: Modifier) {
    androidx.compose.material3.Card(modifier, colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(12.dp)) {
            Text(title, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = BrandRed)
        }
    }
}

/** Everything one sales person has billed, newest first, with the items of each bill. */
@Composable
fun StaffDetailScreen(app: App, nav: Nav, staffId: String) {
    val bills by app.repo.bills.collectAsStateWithLifecycle(emptyList())
    val staff by app.repo.staff.collectAsStateWithLifecycle(emptyList())
    val s = staff.firstOrNull { it.id == staffId }
    val mine = remember(bills, staffId) { bills.filter { it.salesPersonId == staffId }.sortedByDescending { it.createdAt } }
    val now = System.currentTimeMillis()
    val sales = remember(mine) { mine.toSales() }
    val today = Reports.total(sales, startOfToday(), Long.MAX_VALUE)
    val month = Reports.total(sales, Periods.startOfMonth(now), Long.MAX_VALUE)
    val all = Reports.total(sales, 0L, Long.MAX_VALUE)
    val dateFmt = remember { java.text.SimpleDateFormat("dd MMM yyyy, hh:mm a", java.util.Locale.ENGLISH) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
        item {
            Card(Modifier.fillMaxWidth().padding(top = 8.dp), colors = CardDefaults.cardColors(containerColor = SoftGrey)) {
                Column(Modifier.padding(14.dp)) {
                    Text(s?.name ?: "Sales person", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text(s?.mobile?.takeIf { it.isNotBlank() } ?: "No mobile number", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (s != null && !s.active) Text("Inactive", color = RedOut, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReportMini("Today", moneyG(app, today), Modifier.weight(1f))
                        ReportMini("This month", moneyG(app, month), Modifier.weight(1f))
                    }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReportMini("All time", moneyG(app, all), Modifier.weight(1f))
                        ReportMini("Bills", mine.size.toString(), Modifier.weight(1f))
                    }
                }
            }
        }
        item { SectionTitle("Bill history") }
        if (mine.isEmpty()) item { EmptyState("No bills yet for this person.") }
        items(mine, key = { it.id }) { b ->
            val items = parseBillItems(b.itemsJson)
            Column(Modifier.fillMaxWidth().clickable { nav.go(Route.Receipt(b.id)) }.padding(vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(b.billNo, fontWeight = FontWeight.SemiBold)
                        Text(dateFmt.format(java.util.Date(b.createdAt)) + " · " + b.method, fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text((b.customerName.ifBlank { "Walk-in Customer" }), fontSize = 12.sp)
                    }
                    Text(moneyG(app, b.total), fontWeight = FontWeight.Bold)
                }
                Text(items.joinToString(", ") { "${it.article.ifBlank { it.name }} ${it.colour}/${it.size} x${it.qty}" },
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, modifier = Modifier.padding(top = 4.dp))
            }
            HorizontalDivider()
        }
    }
}
