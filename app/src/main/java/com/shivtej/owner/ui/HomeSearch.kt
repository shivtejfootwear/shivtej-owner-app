package com.shivtej.owner.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One searchable place in the app: a screen, setting or feature. Add new features here so the search finds them. */
class SearchEntry(val label: String, val where: String, val route: Route, val keys: String = "")

/** Places that are not on the dashboard shortcuts. Keywords are in Hindi and English. */
private val SEARCH_EXTRAS: List<SearchEntry> = listOf(
    SearchEntry("वापसी (Returns)", "POS / Dashboard", Route.Returns, "return वापसी रिटर्न refund रिफंड exchange एक्सचेंज"),
    SearchEntry("Shop & Bill Settings", "Settings", Route.ShopSettings, "दुकान पता address bill settings नोट्स notes पॉलिसी gst tax"),
    SearchEntry("Bill Design", "Settings", Route.BillDesign, "bill design बिल डिज़ाइन लोगो logo"),
    SearchEntry("Theme & Wallpaper", "Settings", Route.ThemeSettings, "theme थीम रंग wallpaper"),
    SearchEntry("Settings", "App", Route.Settings, "settings सेटिंग pin पिन sync सिंक backup बैकअप"),
    SearchEntry("Customer dues", "Customers", Route.Dues, "उधार due बकाया udhaar"),
    SearchEntry("Customer numbers", "Customers", Route.CustomerNumbers, "नंबर numbers contacts csv"),
    SearchEntry("Deleted bills", "Sales", Route.DeletedBills, "हटाए बिल deleted bills restore"),
    SearchEntry("Change log", "Stock / Reports", Route.ChangeLog, "change log बदलाव price bill edit"),
    SearchEntry("Supplier order list", "Stock", Route.SupplierOrders, "supplier order सप्लायर order list"),
    SearchEntry("Size requests", "Stock", Route.SizeRequests, "साइज़ आया size requests waiting"),
    SearchEntry("Suppliers & Purchases", "Stock", Route.Suppliers, "supplier purchase खरीद"),
    SearchEntry("Admin Portal", "Website", Route.AdminPortal, "website वेबसाइट admin"),
    SearchEntry("Catalog", "Website", Route.Catalog, "catalog कैटलॉग MRP"),
    SearchEntry("Products", "Stock", Route.Products(), "products प्रोडक्ट माल"),
    SearchEntry("Stock / Inventory", "Stock", Route.Stock(), "stock स्टॉक"),
    SearchEntry("Stock check", "Stock", Route.StockCheck, "स्टॉक चेक stock check scan"),
    SearchEntry("No barcode pieces", "Stock", Route.NoBarcode, "बिना बारकोड no barcode"),
    SearchEntry("Reports", "Sales", Route.Reports, "रिपोर्ट report refunds revenue"),
    SearchEntry("Tasks & Reminders", "App", Route.Tasks, "task reminder याद"),
    SearchEntry("Staff & Sales", "Staff", Route.Staff, "staff स्टाफ"),
    SearchEntry("Staff Bills", "Staff", Route.StaffBills, "staff bills स्टाफ बिल"),
    SearchEntry("Bluetooth Printer", "App", Route.Printer, "printer प्रिंटर bluetooth"),
    SearchEntry("AI Assistant", "App", Route.Assistant, "ai assistant सहायक")
)

/** Everything the owner can search: the dashboard shortcuts plus the extra places. */
private val SEARCH_INDEX: List<SearchEntry> by lazy {
    QUICK_CATALOG.map { SearchEntry(it.label, "Dashboard", it.route, it.label) } + SEARCH_EXTRAS
}

/** Search bar on the home screen. Typing shows matching screens and settings; tapping opens it. */
@Composable
fun HomeSearchBar(nav: Nav) {
    var q by remember { mutableStateOf("") }
    val hits = remember(q) {
        val t = q.trim().lowercase()
        if (t.isEmpty()) emptyList()
        else SEARCH_INDEX.filter { (it.label + " " + it.where + " " + it.keys).lowercase().contains(t) }.take(8)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = q, onValueChange = { q = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("सेटिंग, फीचर, रिपोर्ट खोजें…") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = { if (q.isNotEmpty()) IconButton(onClick = { q = "" }) { Icon(Icons.Filled.Close, "Clear") } }
        )
        if (q.isNotBlank() && hits.isEmpty()) {
            Text("कुछ नहीं मिला। दूसरा शब्द लिखें।", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (hits.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column {
                    hits.forEach { e ->
                        Column(Modifier.fillMaxWidth().clickable { q = ""; nav.go(e.route) }
                            .padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text(e.label, fontWeight = FontWeight.SemiBold)
                            Text(e.where, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
