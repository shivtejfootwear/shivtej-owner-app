package com.shivtej.owner.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shivtej.owner.App
import com.shivtej.owner.net.Api
import com.shivtej.owner.net.ApiError
import com.shivtej.owner.util.Images
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Admin Portal: the website text, photos, social links and shipping charge, the same things the website admin page changes.
 * Online orders open the existing Orders screen.
 */
@Composable
fun AdminPortalScreen(app: App, nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val code = app.prefs.ownerCode
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // Website text. The defaults are the same as the website admin page.
    var heroTitle by remember { mutableStateOf("New stock.|New style.") }
    var heroText by remember { mutableStateOf("") }
    var heroAnim by remember { mutableStateOf(true) }
    var heroArticle by remember { mutableStateOf("6102") }
    var bandTitle by remember { mutableStateOf("") }
    var bandText by remember { mutableStateOf("") }
    var instagram by remember { mutableStateOf("") }
    var facebook by remember { mutableStateOf("") }
    var whatsapp by remember { mutableStateOf("") }
    var shipFee by remember { mutableStateOf("") }

    // Website photo URLs (empty = none). They are saved together with the text.
    var heroImage by remember { mutableStateOf("") }
    var heroArtImage by remember { mutableStateOf("") }
    var bandImage by remember { mutableStateOf("") }
    var uploadingSlot by remember { mutableStateOf<String?>(null) }
    var pickSlot by remember { mutableStateOf("") }

    // Until the current website settings are loaded, nothing may be saved (it would overwrite them with blanks).
    val ready = !loading && error == null

    suspend fun call(fn: String, args: JSONObject): Any? =
        withContext(Dispatchers.IO) { Api.rpc(fn, args.put("p_code", code)) }

    fun msg(e: Exception): String {
        val m = e.message.orEmpty()
        return when {
            m.contains("INVALID_OWNER_CODE") -> "Owner code is wrong. Check it in Settings."
            m.contains("BAD_SHIPPING") -> "Shipping charge must be between 0 and 5000."
            m.contains("TOO_BIG") -> "Photo is too big. Choose a smaller photo."
            else -> m.ifBlank { "Could not connect. Check internet and try again." }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        val slot = pickSlot
        if (uri != null && slot.isNotEmpty()) {
            scope.launch {
                uploadingSlot = slot
                try {
                    val path = withContext(Dispatchers.IO) { Images.importUri(ctx, uri) }
                        ?: throw IllegalStateException("This photo can not be read.")
                    val url = withContext(Dispatchers.IO) {
                        Api.uploadSiteMedia(code, slot, File(path).readBytes(), "image/jpeg")
                    }
                    when (slot) {
                        "hero" -> heroImage = url
                        "band" -> bandImage = url
                        else -> heroArtImage = url
                    }
                    toast(ctx, "Photo uploaded. Press Save website.")
                } catch (e: ApiError) {
                    toast(ctx, "Upload failed: ${msg(e)}")
                } catch (e: Exception) {
                    toast(ctx, "Upload failed: ${msg(e)}")
                }
                uploadingSlot = null
            }
        }
    }

    fun pickPhoto(slot: String) {
        pickSlot = slot
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    LaunchedEffect(Unit) {
        try {
            val r = call("owner_get_site_settings", JSONObject()) as? JSONObject
            val s = r?.optJSONObject("site") ?: JSONObject()
            heroTitle = s.optString("heroTitle", "New stock.|New style.")
            heroText = s.optString("heroText", "")
            heroAnim = s.optBoolean("heroAnim", true)
            heroArticle = s.optString("heroArticle", "6102")
            bandTitle = s.optString("bandTitle", "")
            bandText = s.optString("bandText", "")
            instagram = s.optString("instagram", "")
            facebook = s.optString("facebook", "")
            whatsapp = s.optString("whatsapp", "")
            heroImage = s.optString("heroImage", "")
            heroArtImage = s.optString("heroArtImage", "")
            bandImage = s.optString("bandImage", "")
            shipFee = r?.opt("shippingFee")?.toString()?.removeSuffix(".0") ?: ""
        } catch (e: Exception) {
            error = msg(e)
        }
        loading = false
    }

    fun saveWebsite() = scope.launch {
        val v = JSONObject()
            .put("heroTitle", heroTitle.trim())
            .put("heroText", heroText.trim())
            .put("heroAnim", heroAnim)
            .put("heroArticle", heroArticle.trim())
            .put("bandTitle", bandTitle.trim())
            .put("bandText", bandText.trim())
            .put("instagram", instagram.trim())
            .put("facebook", facebook.trim())
            .put("whatsapp", whatsapp.filter { it.isDigit() })
            .put("heroImage", heroImage)
            .put("heroArtImage", heroArtImage)
            .put("bandImage", bandImage)
        try {
            call("owner_save_site_settings", JSONObject().put("p_value", v))
            toast(ctx, "Website saved. Reload the shop page to check.")
        } catch (e: Exception) {
            toast(ctx, "Save failed: ${msg(e)}")
        }
    }

    fun saveShipping() = scope.launch {
        val n = shipFee.trim().toDoubleOrNull()
        if (n == null || n < 0 || n > 5000) { toast(ctx, "Enter an amount between 0 and 5000."); return@launch }
        try {
            call("owner_set_shipping_fee", JSONObject().put("p_fee", n))
            toast(ctx, "Shipping charge saved.")
        } catch (e: Exception) {
            toast(ctx, "Save failed: ${msg(e)}")
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        if (loading) Text("Loading...", fontSize = 13.sp)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }

        SectionTitle("Online orders")
        Text("Accept, pack, ship and add tracking. Same as the website admin Orders list.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { nav.go(Route.Orders("all")) }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text("Open online orders")
        }

        SectionTitle("Shipping charge (website)")
        Field("Shipping charge (0 to 5000)", shipFee, keyboard = KeyboardType.Decimal) { shipFee = it }
        Button(onClick = { saveShipping() }, enabled = ready, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text("Save shipping charge")
        }

        SectionTitle("Edit website")
        Text("Use | to split the heading into two lines. Example: New stock.|New style.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Field("Main heading", heroTitle) { heroTitle = it }
        Field("Short text", heroText, single = false) { heroText = it }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(heroAnim, { heroAnim = it }); Spacer(Modifier.width(8.dp)); Text("Animate the top text")
        }
        Field("Product article code (opens when the top photo is clicked)", heroArticle) { heroArticle = it }

        SectionTitle("Website photos")
        PhotoRow("1. Top photo", heroImage, uploadingSlot == "hero", ready,
            onPick = { pickPhoto("hero") }, onRemove = { heroImage = "" }, removeLabel = "Remove")
        PhotoRow("2. Top sandal photo (floating)", heroArtImage, uploadingSlot == "heroart", ready,
            onPick = { pickPhoto("heroart") }, onRemove = { heroArtImage = "" }, removeLabel = "Use default photo",
            emptyText = "Default photo is used")
        PhotoRow("3. Middle band photo", bandImage, uploadingSlot == "band", ready,
            onPick = { pickPhoto("band") }, onRemove = { bandImage = "" }, removeLabel = "Remove")

        SectionTitle("Middle band")
        Field("Band heading", bandTitle) { bandTitle = it }
        Field("Band text", bandText, single = false) { bandText = it }

        SectionTitle("Social and WhatsApp")
        Field("Instagram link", instagram) { instagram = it }
        Field("Facebook link", facebook) { facebook = it }
        Field("WhatsApp number (with country code, without +)", whatsapp, keyboard = KeyboardType.Phone) { whatsapp = it }

        Button(onClick = { saveWebsite() }, enabled = ready && uploadingSlot == null,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text("Save website")
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PhotoRow(
    title: String, url: String, busy: Boolean, enabled: Boolean,
    onPick: () -> Unit, onRemove: () -> Unit,
    removeLabel: String = "Remove", emptyText: String = "No photo"
) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(if (url.isBlank()) emptyText else "Photo is set", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPick, enabled = enabled && !busy) {
                    Text(if (busy) "Uploading..." else "Choose photo")
                }
                if (url.isNotBlank()) {
                    TextButton(onClick = onRemove, enabled = enabled && !busy) { Text(removeLabel) }
                }
            }
        }
    }
}
