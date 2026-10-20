@file:OptIn(ExperimentalMaterial3Api::class)

package com.shivtej.owner.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.shivtej.owner.App
import com.shivtej.owner.net.Api
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// ------------------------------------------------------------------ navigation
sealed interface Route {
    object Dashboard : Route
    data class Products(val filter: String = "all") : Route
    data class ProductEdit(val id: String?) : Route
    data class Stock(val tab: Int = 0, val filter: String = "all") : Route
    object Pos : Route
    data class Receipt(val billId: String) : Route
    data class Orders(val mode: String = "all") : Route
    data class OrderDetail(val id: String) : Route
    object Customers : Route
    object CustomersOnline : Route
    object ChangeLog : Route
    object SupplierOrders : Route
    object SizeRequests : Route
    object Reports : Route
    object Assistant : Route
    object Settings : Route
    // ---- update 2
    object Staff : Route
    object CustomerNew : Route
    data class StaffDetail(val id: String) : Route
    data class CustomerDetail(val mobile: String) : Route
    object Suppliers : Route
    data class SupplierDetail(val id: String) : Route
    data class PurchaseForm(val supplierId: String, val billId: String? = null) : Route
    object Printer : Route
    object ShopSettings : Route
    object BillDesign : Route
    // ---- update 4
    object ThemeSettings : Route
    object Tasks : Route
    // ---- customer dues (opened from the dashboard): the customers who owe money, highest due first
    object Dues : Route
    // ---- admin portal (website text, shipping charge; orders open the Orders screen)
    object AdminPortal : Route
    // ---- customer numbers (share contacts or CSV)
    object CustomerNumbers : Route
    // ---- deleted bills (history of every deleted bill; they can be restored, never erased)
    object DeletedBills : Route
    // ---- catalog (website products with MRP only, to show customers)
    object Catalog : Route
    // ---- staff bills (pending bills the managers sent from the Shivtej Managers app)
    object StaffBills : Route
}

class Nav(private val stack: MutableList<Route>) {
    val current: Route get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1
    fun go(r: Route) { stack.add(r) }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun top(r: Route) { stack.clear(); stack.add(Route.Dashboard); if (r != Route.Dashboard) stack.add(r) }
    fun replace(r: Route) { if (stack.size > 1) stack.removeAt(stack.lastIndex); stack.add(r) }
}

// ------------------------------------------------------------------ small helpers
fun imgModel(s: String): Any = if (s.startsWith("/")) File(s) else s

fun toast(ctx: android.content.Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

fun money(app: App, v: Double): String = com.shivtej.owner.util.Receipt.money(app.prefs, v)

/** Same as [money] but with thousands separators (1,23,456) - used on the dashboard and reports of update 2. */
fun moneyG(app: App, v: Double): String = com.shivtej.owner.util.Fmt.moneyG(app.prefs.currency.ifBlank { "₹" }, v)

val BrandRed = Color(0xFFE30613)
val BrandInk = Color(0xFF111111)
val SoftRed = Color(0xFFFDECEC)
val SoftGrey = Color(0xFFF6F6F7)

val GreenOk = Color(0xFF2E7D32)
val AmberLow = Color(0xFFEF6C00)
val RedOut = Color(0xFFC62828)

/** Private product photos are saved in B2 as names like "products/<id>/<uuid>.jpg". Their signed URLs are cached. */
private val signedPhotoCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()

/** Signed URL for one private product photo name. The URL lasts 1 hour, so it is cached for 50 minutes. */
suspend fun privatePhotoUrl(name: String): String? = withContext(Dispatchers.IO) {
    val now = System.currentTimeMillis()
    signedPhotoCache[name]?.let { (url, until) -> if (now < until) return@withContext url }
    val pid = name.split("/").getOrNull(1) ?: return@withContext null
    try {
        val url = Api.productPhotoUrl(pid, name)
        signedPhotoCache[name] = url to (now + 50 * 60_000L)
        url
    } catch (e: Exception) {
        null
    }
}

@Composable
fun Thumb(src: String?, size: Dp = 56.dp) {
    val isPrivate = src != null && src.startsWith("products/")
    val remoteUrl by produceState<String?>(null, src) {
        value = if (src != null && isPrivate) privatePhotoUrl(src) else null
    }
    val model: Any? = when {
        src == null -> null
        isPrivate -> remoteUrl
        else -> imgModel(src)
    }
    if (model == null) {
        Box(
            Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Filled.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    } else {
        AsyncImage(
            model = model, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(RoundedCornerShape(8.dp))
        )
    }
}

@Composable
fun StockBadge(qty: Int, low: Int) {
    val (t, c) = when {
        qty <= 0 -> "Out of stock" to RedOut
        qty <= low -> "Low: $qty" to AmberLow
        else -> "In stock: $qty" to GreenOk
    }
    Text(t, color = c, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        placeholder = { Text(hint) },
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
fun SectionTitle(t: String) {
    Text(t, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
fun EmptyState(msg: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(msg, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Returns a function that runs an action once the CAMERA permission is granted. */
@Composable
fun rememberWithCamera(): (() -> Unit) -> Unit {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) pending?.invoke() else toast(ctx, "Camera permission is needed")
        pending = null
    }
    return { action ->
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) action()
        else { pending = action; launcher.launch(Manifest.permission.CAMERA) }
    }
}
