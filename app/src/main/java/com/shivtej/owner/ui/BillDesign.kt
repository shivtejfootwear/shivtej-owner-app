@file:OptIn(ExperimentalMaterial3Api::class)

package com.shivtej.owner.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shivtej.owner.App
import com.shivtej.owner.data.*
import com.shivtej.owner.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * The receipt used for the preview: the newest real bill (so the preview looks like what the owner really prints),
 * or a neutral sample when no bill exists yet. The shop details shown are the ones on the screen.
 */
fun previewReceipt(prefs: Prefs, latest: BillE?, shop: ShopInfo): ReceiptData {
    if (latest != null) return BillMeta.buildReceipt(prefs, latest).first.copy(shop = shop)
    val lines = listOf(
        RLine(1, "SAMPLE-A", "Sample item", "Black", "8", 1, 650.0, 500.0),
        RLine(2, "SAMPLE-B", "Sample item", "Navy", "9", 2, 450.0, 400.0)
    )
    val t = BillMath.compute(lines.map { MathLine(it.mrp, it.rate, it.qty) }, prefs.taxPercent)
    return ReceiptData(
        shop = shop, billNo = "PREVIEW-0001", dateMillis = System.currentTimeMillis(),
        customerName = "Customer name", customerMobile = "9999999999", customerAddress = "City",
        salesPerson = "Sales person", lines = lines, totalMrp = t.totalMrp, totalDiscount = t.totalDiscount,
        subTotal = t.subTotal, taxPercent = t.taxPercent, taxAmount = t.taxAmount, grandTotal = t.grandTotal,
        method = "Cash", amountPaid = t.grandTotal, balance = 0.0
    )
}

@Composable
fun BillDesignScreen(app: App, nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val bills by app.repo.bills.collectAsStateWithLifecycle(emptyList())
    var theme by remember { mutableStateOf(BillMeta.currentTheme(app.prefs)) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    val shop = BillMeta.currentShop(app.prefs)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val path = withContext(Dispatchers.IO) { Images.importUri(ctx, uri) }
            if (path != null) theme = theme.copy(bgImage = path, bgStyle = "image")
            else toast(ctx, "Could not open that picture. Try another one.")
        }
    }

    // the preview is drawn again a moment after each change (not on every slider tick)
    LaunchedEffect(theme) {
        delay(350)
        preview = withContext(Dispatchers.Default) {
            Invoice.invoice(ctx, previewReceipt(app.prefs, bills.firstOrNull(), shop), theme)
        }
    }

    LazyColumnPage {
        item { SectionTitle("Ready-made themes") }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BillThemes.presets.forEach { p ->
                    val selected = theme.id == p.id
                    Card(
                        onClick = { theme = BillThemes.apply(theme, p.id) },
                        border = if (selected) androidx.compose.foundation.BorderStroke(2.dp, BrandRed) else null,
                        colors = CardDefaults.cardColors(containerColor = Color.White)
                    ) {
                        Column(Modifier.padding(10.dp).width(118.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(Modifier.size(18.dp).background(Color(p.accent), CircleShape))
                                Box(Modifier.size(18.dp).background(Color(p.accent2), CircleShape))
                                Box(Modifier.size(18.dp).background(Color(p.tint), CircleShape).border(1.dp, Color(0xFFDDDDDD), CircleShape))
                            }
                            Text(p.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp), maxLines = 2)
                        }
                    }
                }
            }
        }

        item { SectionTitle("Background") }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BillThemes.backgrounds.forEach { (k, l) ->
                    if (k == "image") Chip(l, theme.bgStyle == k) {
                        if (theme.bgImage.isBlank()) picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        else theme = theme.copy(bgStyle = k)
                    }
                    else Chip(l, theme.bgStyle == k) { theme = theme.copy(bgStyle = k) }
                }
            }
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Icon(Icons.Filled.AddPhotoAlternate, null); Spacer(Modifier.width(6.dp)); Text(if (theme.bgImage.isBlank()) "Upload my picture" else "Change my picture")
                }
                if (theme.bgImage.isNotBlank()) TextButton(onClick = { theme = theme.copy(bgImage = "", bgStyle = "plain") }) { Text("Remove") }
            }
            Text("The picture is shown faintly behind the bill. Keep the strength low so the text stays easy to read.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Background strength: ${BillThemes.strength(theme)}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
            Slider(
                value = BillThemes.strength(theme).toFloat(),
                onValueChange = { theme = theme.copy(bgStrength = it.roundToInt()) },
                valueRange = 0f..BillTheme.MAX_STRENGTH.toFloat()
            )
        }

        item { SectionTitle("What to show on the bill") }
        item { ToggleRow("Shop logo", theme.showLogo) { theme = theme.copy(showLogo = it) } }
        item { ToggleRow("Logo and shop name centred", theme.centerHeader) { theme = theme.copy(centerHeader = it) } }
        item { ToggleRow("Tagline text (footwear for every step)", theme.showTagline) { theme = theme.copy(showTagline = it) } }
        item { ToggleRow("Website QR code (needs a website in Shop settings)", theme.showQr) { theme = theme.copy(showQr = it) } }
        item { ToggleRow("Instagram QR code (needs Instagram in Shop settings)", theme.showInstagramQr) { theme = theme.copy(showInstagramQr = it) } }
        item { ToggleRow("Notes / return policy", theme.showNotes) { theme = theme.copy(showNotes = it) } }
        item { ToggleRow("Thank you text", theme.showThanks) { theme = theme.copy(showThanks = it) } }
        item { ToggleRow("Footer text at the bottom", theme.showFooter) { theme = theme.copy(showFooter = it) } }

        item { SectionTitle("Preview") }
        item {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                val bmp = preview
                if (bmp == null) Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else Image(bmp.asImageBitmap(), "Bill preview", Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
            }
            Text(
                if (bills.isEmpty()) "No bills yet, so this is a sample. It uses the shop details from Shop settings." else
                    "Preview uses your newest bill (${bills.first().billNo}) with the shop details from Shop settings.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)
            )
        }
        item {
            Text("Only colours, background and which blocks are shown change. Totals, GST, payments and stock are never affected. " +
                "Bills already made keep the design they were printed with.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
        item {
            Button(
                onClick = {
                    BillMeta.saveTheme(app.prefs, theme)
                    toast(ctx, "Saved as the default bill design")
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BrandRed)
            ) { Text("Save as default bill design", fontWeight = FontWeight.Bold) }
        }
        item {
            OutlinedButton(onClick = { theme = BillTheme() }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Reset to Shivtej Red") }
        }
    }
}

@Composable
private fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

/** A plain scrolling column with the same margins as the other screens. */
@Composable
fun LazyColumnPage(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    androidx.compose.foundation.lazy.LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content
    )
}
