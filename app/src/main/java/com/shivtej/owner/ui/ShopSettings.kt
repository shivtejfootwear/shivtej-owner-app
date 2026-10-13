@file:OptIn(ExperimentalMaterial3Api::class)

package com.shivtej.owner.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shivtej.owner.App
import com.shivtej.owner.data.*
import com.shivtej.owner.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shop and bill details, typed in without any code. Saved on the phone; every bill keeps the details it was made with,
 * so changing the shop address today never changes an old bill.
 */
@Composable
fun ShopSettingsScreen(app: App, nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val p = app.prefs
    val bills by app.repo.bills.collectAsStateWithLifecycle(emptyList())
    var name by remember { mutableStateOf(p.storeName) }
    var tagline by remember { mutableStateOf(p.shopTagline) }
    var address by remember { mutableStateOf(p.storeAddress) }
    var phone by remember { mutableStateOf(p.storePhone) }
    var email by remember { mutableStateOf(p.shopEmail) }
    var website by remember { mutableStateOf(p.shopWebsite) }
    var instagram by remember { mutableStateOf(p.shopInstagram) }
    var whatsapp by remember { mutableStateOf(p.shopWhatsapp) }
    var gstin by remember { mutableStateOf(p.shopGstin) }
    var tax by remember { mutableStateOf(BillMath.plain(p.taxPercent)) }
    var thanks by remember { mutableStateOf(p.billThanks) }
    var footer by remember { mutableStateOf(p.billFooter) }
    var notes by remember { mutableStateOf(p.billNotes) }
    var logo by remember { mutableStateOf(p.shopLogoPath) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val path = withContext(Dispatchers.IO) { Images.importUri(ctx, uri) }
            if (path != null) logo = path else toast(ctx, "Could not open that picture")
        }
    }

    val websiteUrl = Links.website(website)
    val instaUrl = Links.instagram(instagram)
    val taxValue = BillMath.parseAmount(tax)
    val formError: String? = when {
        phone.isNotBlank() && phone.count { it.isDigit() } < 10 -> "Mobile number must have 10 digits."
        taxValue == null && tax.isNotBlank() -> "GST % must be a number, for example 5 (or 0)."
        tax.isBlank() -> null
        else -> null
    }

    fun shopNow() = ShopInfo(
        name = name.trim().ifBlank { "Shivtej Footwear" }, tagline = tagline.trim(), address = address.trim(),
        phone = phone.trim(), email = email.trim(), website = websiteUrl, instagram = instaUrl,
        whatsapp = whatsapp.trim(), gstin = gstin.trim(), footer = footer.trim(), thanks = thanks.trim(),
        notes = notes.split("\n").map { it.trim() }.filter { it.isNotEmpty() },
        logoPath = logo, currency = p.currency.ifBlank { "₹" }
    )

    LazyColumnPage {
        item { SectionTitle("Shop details") }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
                    val bmp = remember(logo) { Invoice.loadLogo(ctx, shopNow()) }
                    bmp?.let { Image(it.asImageBitmap(), "Shop logo", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(if (logo.isBlank()) "Built-in Shivtej logo" else "Your logo", fontWeight = FontWeight.SemiBold)
                    OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Upload logo") }
                    if (logo.isNotBlank()) TextButton(onClick = { logo = "" }) { Text("Use built-in logo") }
                }
            }
        }
        item { OutlinedTextField(name, { name = it }, label = { Text("Shop name") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(tagline, { tagline = it }, label = { Text("Tagline") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(address, { address = it }, label = { Text("Address") }, minLines = 2, modifier = Modifier.fillMaxWidth()) }
        item {
            OutlinedTextField(phone, { phone = it.take(20) }, label = { Text("Mobile number") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
        }
        item {
            OutlinedTextField(email, { email = it.trim() }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        }
        item {
            OutlinedTextField(website, { website = it.trim() }, label = { Text("Website (for example shivtej.in)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        if (websiteUrl.isNotEmpty()) item { QrPreview("Website QR", websiteUrl) }
        item {
            OutlinedTextField(instagram, { instagram = it.trim() }, label = { Text("Instagram (@name or link)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        if (instaUrl.isNotEmpty()) item { QrPreview("Instagram QR", instaUrl) }
        item {
            OutlinedTextField(whatsapp, { whatsapp = it.filter { c -> c.isDigit() || c == '+' }.take(16) }, label = { Text("WhatsApp number") },
                singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
        }
        item { OutlinedTextField(gstin, { gstin = it.uppercase().take(15) }, label = { Text("GSTIN (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        item {
            OutlinedTextField(tax, { tax = it.filter { c -> c.isDigit() || c == '.' }.take(5) }, label = { Text("GST % added to bills (0 = none)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = tax.isNotBlank() && taxValue == null)
        }

        item { SectionTitle("Bill text") }
        item { OutlinedTextField(thanks, { thanks = it.take(60) }, label = { Text("Thank you line") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        item {
            OutlinedTextField(notes, { notes = it }, label = { Text("Notes / return & exchange policy (one per line)") },
                minLines = 4, modifier = Modifier.fillMaxWidth())
        }
        item {
            OutlinedTextField(footer, { footer = it }, label = { Text("Footer (bottom of the bill)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        }
        item {
            OutlinedButton(onClick = { nav.go(Route.BillDesign) }, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Bill theme and design") }
        }

        item {
            Text("Changes apply to new bills. Bills already made keep the shop details they were printed with.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        formError?.let { item { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) } }
        message?.let { item { Text(it, color = Color(0xFF2E7D32), fontSize = 13.sp, fontWeight = FontWeight.SemiBold) } }
        item {
            Button(
                enabled = formError == null,
                onClick = {
                    p.storeName = name.trim().ifBlank { "Shivtej Footwear" }
                    p.shopTagline = tagline.trim()
                    p.storeAddress = address.trim()
                    p.storePhone = phone.trim()
                    p.shopEmail = email.trim()
                    p.shopWebsite = website.trim()
                    p.shopInstagram = instagram.trim()
                    p.shopWhatsapp = whatsapp.trim()
                    p.shopGstin = gstin.trim()
                    p.taxPercent = taxValue ?: 0.0
                    p.billThanks = thanks.trim()
                    p.billFooter = footer.trim()
                    p.billNotes = notes
                    p.shopLogoPath = logo
                    p.shopEditedAt = System.currentTimeMillis()
                    // also kept on the server: a new phone or a reinstall gets these details back
                    scope.launch { app.repo.queueShopSettings(ShopSync.payload(p)) }
                    message = "Saved. New bills will use these details, and they are sent to the server (when the phone is online)."
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BrandRed)
            ) { Text("Save shop details", fontWeight = FontWeight.Bold) }
        }
        item {
            OutlinedButton(onClick = {
                scope.launch {
                    val bmp = withContext(Dispatchers.Default) {
                        Invoice.invoice(ctx, previewReceipt(p, bills.firstOrNull(), shopNow()), BillMeta.currentTheme(p))
                    }
                    preview = bmp
                }
            }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Preview bill with these details") }
        }
        preview?.let { bmp ->
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Image(bmp.asImageBitmap(), "Bill preview", Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                }
            }
        }
    }
}

@Composable
private fun QrPreview(title: String, text: String) {
    val bmp = remember(text) { Qr.bitmap(text, 240) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        bmp?.let { Image(it.asImageBitmap(), title, Modifier.size(96.dp)) }
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text("Made automatically from the address above. It is printed on every bill.", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
