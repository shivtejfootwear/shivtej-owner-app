package com.shivtej.owner.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shivtej.owner.data.Prefs

/** One shortcut the owner can show on the dashboard. [id] is what is saved on the phone, so an id must never be renamed. */
class QuickActionDef(val id: String, val label: String, val icon: ImageVector, val color: Color, val route: Route)

/** Every shortcut that can be shown. */
val QUICK_CATALOG: List<QuickActionDef> = listOf(
    QuickActionDef("bill", "New Bill", Icons.Filled.Receipt, BrandRed, Route.Pos),
    QuickActionDef("add_customer", "Add Customer", Icons.Filled.PersonAdd, Color(0xFF1565C0), Route.CustomerNew),
    QuickActionDef("add_product", "Add Product", Icons.Filled.AddCircle, Color(0xFF2E7D32), Route.ProductEdit(null)),
    QuickActionDef("staff", "Staff", Icons.Filled.Group, Color(0xFF6A1B9A), Route.Staff),
    QuickActionDef("customers", "Customers", Icons.Filled.People, Color(0xFFEF6C00), Route.Customers),
    QuickActionDef("dues", "Customer Dues", Icons.Filled.Warning, RedOut, Route.Dues),
    QuickActionDef("reports", "Reports", Icons.Filled.BarChart, Color(0xFF00838F), Route.Reports),
    QuickActionDef("returns", "Returns / Exchange", Icons.Filled.Replay, BrandInk, Route.Returns),
    QuickActionDef("printer", "Printer", Icons.Filled.Print, BrandInk, Route.Printer),
    QuickActionDef("orders", "Online Orders", Icons.Filled.ShoppingCart, Color(0xFF283593), Route.Orders("all")),
    QuickActionDef("stock", "Stock / Inventory", Icons.Filled.Warehouse, Color(0xFF2E7D32), Route.Stock()),
    QuickActionDef("suppliers", "Suppliers", Icons.Filled.Storefront, Color(0xFF5D4037), Route.Suppliers),
    QuickActionDef("tasks", "Tasks & Reminders", Icons.Filled.Checklist, Color(0xFF00695C), Route.Tasks),
    QuickActionDef("assistant", "AI Assistant", Icons.Filled.SmartToy, Color(0xFF455A64), Route.Assistant),
    QuickActionDef("theme", "Theme", Icons.Filled.Settings, Color(0xFF6D4C41), Route.ThemeSettings),
    QuickActionDef("admin_portal", "Admin Portal", Icons.Filled.AdminPanelSettings, Color(0xFF880E4F), Route.AdminPortal)
)

/** The shortcuts for a saved list of ids, in that order. Unknown and repeated ids are ignored. */
fun quickActionsFrom(csv: String): List<QuickActionDef> =
    csv.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        .mapNotNull { id -> QUICK_CATALOG.firstOrNull { it.id == id } }

private fun List<String>.swapAt(i: Int, j: Int): List<String> {
    if (i !in indices || j !in indices) return this
    val m = toMutableList()
    val t = m[i]; m[i] = m[j]; m[j] = t
    return m
}

/**
 * Edit the dashboard Quick actions: choose which shortcuts show, their order, and restore the default.
 * Nothing is saved until Done. [onSave] gets the new comma separated list of ids.
 */
@Composable
fun QuickActionsEditor(initialCsv: String, onSave: (String) -> Unit, onCancel: () -> Unit) {
    var ids by remember { mutableStateOf(quickActionsFrom(initialCsv).map { it.id }) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Edit Quick actions") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("On your dashboard, top to bottom. Use the arrows to change the order.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (ids.isEmpty()) Text("No shortcuts yet. Add some from the list below.", fontSize = 13.sp)
                ids.forEachIndexed { i, id ->
                    val a = QUICK_CATALOG.firstOrNull { it.id == id } ?: return@forEachIndexed
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(a.icon, null, tint = readableTone(a.color), modifier = Modifier.size(20.dp))
                        Text(a.label, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 8.dp))
                        IconButton(onClick = { ids = ids.swapAt(i, i - 1) }, enabled = i > 0) {
                            Icon(Icons.Filled.KeyboardArrowUp, "Move up")
                        }
                        IconButton(onClick = { ids = ids.swapAt(i, i + 1) }, enabled = i < ids.lastIndex) {
                            Icon(Icons.Filled.KeyboardArrowDown, "Move down")
                        }
                        IconButton(onClick = { ids = ids.filter { it != id } }) { Icon(Icons.Filled.Close, "Remove") }
                    }
                }
                Text("Available shortcuts", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                QUICK_CATALOG.filter { it.id !in ids }.forEach { a ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(a.icon, null, tint = readableTone(a.color), modifier = Modifier.size(20.dp))
                        Text(a.label, modifier = Modifier.weight(1f).padding(start = 8.dp))
                        TextButton(onClick = { ids = ids + a.id }) { Text("Add") }
                    }
                }
                TextButton(onClick = { ids = quickActionsFrom(Prefs.DEFAULT_QUICK_ACTIONS).map { it.id } }) {
                    Text("Restore default arrangement")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(ids.joinToString(",")) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}
