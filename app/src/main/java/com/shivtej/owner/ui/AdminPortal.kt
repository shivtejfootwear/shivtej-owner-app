package com.shivtej.owner.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shivtej.owner.App
import com.shivtej.owner.net.Api
import com.shivtej.owner.net.ApiError
import com.shivtej.owner.util.Images
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Videos for the top, the middle band and the brand film box: up to 50 MB (photos are sent as small JPEGs). */
private const val MAX_VIDEO_BYTES = 50L * 1024 * 1024

/** Extra screens in the home slider (the red top screen is always there and is not counted). */
private const val MAX_SLIDES = 6

/** Season animations on the website. Same keys and order as season.js in the website (calendar order). */
private data class SeasonItem(val key: String, val hi: String, val en: String, val what: String)
private val SEASONS = listOf(
    SeasonItem("sankranti", "मकर संक्रांति", "Makar Sankranti", "पतंगें, हवा की आवाज़"),
    SeasonItem("republic", "गणतंत्र / स्वतंत्रता दिवस", "Republic & Independence Day", "तिरंगे की पंखुड़ियाँ, ढोल और घंटी"),
    SeasonItem("holi", "होली", "Holi", "उड़ता गुलाल, ढोल"),
    SeasonItem("padwa", "गुढी पाडवा", "Gudi Padwa", "आम के पत्ते और गेंदे का तोरण, घंटी"),
    SeasonItem("summer", "गर्मी", "Summer", "धूप, तितलियाँ, चिड़ियों की आवाज़"),
    SeasonItem("eid", "ईद", "Eid", "चाँद, तारे, कंदील, घंटियाँ"),
    SeasonItem("monsoon", "बरसात", "Monsoon", "बारिश, बिजली, बारिश की आवाज़"),
    SeasonItem("ganesh", "गणपति", "Ganesh Chaturthi", "जास्वंद और गेंदे के फूल, ढोल-ताशा, घंटी"),
    SeasonItem("navratri", "नवरात्रि / दसरा", "Navratri & Dussehra", "रंगीन पंखुड़ियाँ, सुनहरी चमक, डांडिया"),
    SeasonItem("diwali", "दिवाली", "Diwali", "दीये, आकाश कंदील, आतिशबाज़ी, पटाखे"),
    SeasonItem("winter", "सर्दी", "Winter", "बर्फ़, ठंडी हवा"),
    SeasonItem("christmas", "क्रिसमस", "Christmas", "रंगीन लाइटें, बर्फ़, घंटियाँ"),
    SeasonItem("newyear", "नया साल", "New Year", "कंफ़ेटी, सुनहरी आतिशबाज़ी")
)

/** One extra screen of the home slider. kind is "photo" or "video" (empty url = plain red background). */
private data class SlideDraft(
    val isVideo: Boolean = false, val url: String = "", val title: String = "",
    val text: String = "", val article: String = "", val active: Boolean = true
)

/** One coupon from the database. category "" = all categories. */
private data class CouponRow(
    val code: String, val kind: String, val value: Double, val category: String,
    val active: Boolean, val used: Int
)

/**
 * Admin Portal: the website text, photos, home slider, coupons, social links and packing charge,
 * the same things the website admin page changes.
 * Online orders open the existing Orders screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    var maintOn by remember { mutableStateOf(false) }
    var maintText by remember { mutableStateOf("") }
    var announceOn by remember { mutableStateOf(false) }
    var announceText by remember { mutableStateOf("") }
    var freeAbove by remember { mutableStateOf("") }
    var trustOn by remember { mutableStateOf(false) }
    var trust1 by remember { mutableStateOf("") }
    var trust2 by remember { mutableStateOf("") }
    var trust3 by remember { mutableStateOf("") }
    var promoOn by remember { mutableStateOf(false) }
    var promoText by remember { mutableStateOf("") }
    var promoCode by remember { mutableStateOf("") }
    var promoTerms by remember { mutableStateOf("") }
    var storeMapLink by remember { mutableStateOf("") }
    var storeTiming by remember { mutableStateOf("") }
    var featuredArticles by remember { mutableStateOf("") }
    var lowStockOn by remember { mutableStateOf(false) }
    var lowStockLimit by remember { mutableStateOf("3") }
    var sliderAuto by remember { mutableStateOf(false) }
    var sliderSeconds by remember { mutableStateOf("5") }
    var heroBtnText by remember { mutableStateOf("Shop now") }
    var heroBtnLink by remember { mutableStateOf("") }
    var revealOn by remember { mutableStateOf(false) }
    val allOrders by app.repo.orders.collectAsStateWithLifecycle(emptyList())
    val shopCust by app.repo.posCustomers.collectAsStateWithLifecycle(emptyList())
    val webCust by app.repo.customers.collectAsStateWithLifecycle(emptyList())
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
    var filmVideo by remember { mutableStateOf("") }
    var filmArticle by remember { mutableStateOf("") }
    var uploadingSlot by remember { mutableStateOf<String?>(null) }
    var pickSlot by remember { mutableStateOf("") }
    var pickIndex by remember { mutableStateOf(-1) }

    // Home slider: the extra screens, in order. They are saved with "Save website".
    val slides = remember { mutableStateListOf<SlideDraft>() }

    // Coupons: saved one by one with "Save coupon" (not with "Save website").
    var coupons by remember { mutableStateOf<List<CouponRow>>(emptyList()) }
    var cCode by remember { mutableStateOf("") }
    var cKind by remember { mutableStateOf("percent") }
    var cValue by remember { mutableStateOf("") }
    var cCategory by remember { mutableStateOf("") }
    val categories = remember {
        app.repo.prefs.categoryList.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    // Today's offer popup on the website. Its coupon is saved together with it (only when the popup is on).
    var offerOn by remember { mutableStateOf(false) }
    var offerTitle by remember { mutableStateOf("Today's offer") }
    var offerText by remember { mutableStateOf("") }
    var offerCode by remember { mutableStateOf("") }
    var offerKind by remember { mutableStateOf("percent") }
    var offerValue by remember { mutableStateOf("") }
    var offerCategory by remember { mutableStateOf("") }

    // Season animation on the website ("" = all off). Only one season can be on.
    var season by remember { mutableStateOf("") }

    // Until the current website settings are loaded, nothing may be saved (it would overwrite them with blanks).
    val ready = !loading && error == null

    suspend fun call(fn: String, args: JSONObject): Any? =
        withContext(Dispatchers.IO) { Api.rpc(fn, args.put("p_code", code)) }

    fun msg(e: Exception): String {
        val m = e.message.orEmpty()
        return when {
            m.contains("INVALID_OWNER_CODE") -> "Owner code is wrong. Check it in Settings."
            m.contains("BAD_SHIPPING") -> "Packing charge must be between 0 and 5000."
            m.contains("BAD_COUPON_CODE") -> "Coupon code must be 3 to 20 letters or numbers."
            m.contains("BAD_COUPON_KIND") -> "Choose rupees off or percent off."
            m.contains("BAD_COUPON_VALUE") -> "Value is not right. Percent must be 1 to 100, rupees more than 0."
            m.contains("TOO_BIG") -> "File is too big. Photos up to 5 MB, videos up to 50 MB."
            else -> m.ifBlank { "Could not connect. Check internet and try again." }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        val slot = pickSlot
        val idx = pickIndex
        if (uri != null && slot.isNotEmpty()) {
            scope.launch {
                uploadingSlot = if (slot == "slide") "slide$idx" else slot
                try {
                    val mime = ctx.contentResolver.getType(uri).orEmpty()
                    val isVideo = mime.startsWith("video/")
                    val url = if (isVideo) {
                        withContext(Dispatchers.IO) {
                            val size = ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                            if (size > MAX_VIDEO_BYTES) throw IllegalStateException("TOO_BIG")
                            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                                ?: throw IllegalStateException("This video can not be read.")
                            if (bytes.size > MAX_VIDEO_BYTES) throw IllegalStateException("TOO_BIG")
                            Api.uploadSiteMedia(code, slot, bytes, mime)
                        }
                    } else {
                        val path = withContext(Dispatchers.IO) { Images.importUri(ctx, uri) }
                            ?: throw IllegalStateException("This photo can not be read.")
                        withContext(Dispatchers.IO) {
                            Api.uploadSiteMedia(code, slot, File(path).readBytes(), "image/jpeg")
                        }
                    }
                    when (slot) {
                        "hero" -> heroImage = url
                        "band" -> bandImage = url
                        "film" -> filmVideo = url
                        "slide" -> if (idx in slides.indices) slides[idx] = slides[idx].copy(url = url, isVideo = isVideo)
                        else -> heroArtImage = url
                    }
                    toast(ctx, if (isVideo) "Video uploaded. Press Save website." else "Photo uploaded. Press Save website.")
                } catch (e: ApiError) {
                    toast(ctx, "Upload failed: ${msg(e)}")
                } catch (e: Exception) {
                    toast(ctx, "Upload failed: ${msg(e)}")
                }
                uploadingSlot = null
            }
        }
    }

    fun pickPhoto(slot: String, index: Int = -1) {
        pickSlot = slot
        pickIndex = index
        // The floating sandal picture stays photo-only; the top, the middle band, the brand film and the slider screens accept video too.
        val kind = if (slot == "heroart") ActivityResultContracts.PickVisualMedia.ImageOnly
                   else ActivityResultContracts.PickVisualMedia.ImageAndVideo
        picker.launch(PickVisualMediaRequest(kind))
    }

    suspend fun loadCoupons() {
        val arr = call("owner_list_coupons", JSONObject()) as? JSONArray ?: return
        coupons = (0 until arr.length()).mapNotNull { k ->
            arr.optJSONObject(k)?.let { o ->
                CouponRow(
                    code = o.optString("code"),
                    kind = o.optString("kind"),
                    value = o.optDouble("value", 0.0),
                    category = if (o.isNull("category")) "" else o.optString("category"),
                    active = o.optBoolean("active", true),
                    used = o.optInt("used", 0)
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        try {
            val r = call("owner_get_site_settings", JSONObject()) as? JSONObject
            val s = r?.optJSONObject("site") ?: JSONObject()
            heroTitle = s.optString("heroTitle", "New stock.|New style.")
            heroText = s.optString("heroText", "")
            heroAnim = s.optBoolean("heroAnim", true)
            maintOn = s.optBoolean("maintenanceOn", false)
            maintText = s.optString("maintenanceText", "")
            announceOn = s.optBoolean("announceOn", false)
            announceText = s.optString("announceText", "")
            freeAbove = s.optString("freeDeliveryAbove", "")
            trustOn = s.optBoolean("trustOn", false)
            trust1 = s.optString("trust1", ""); trust2 = s.optString("trust2", ""); trust3 = s.optString("trust3", "")
            promoOn = s.optBoolean("promoOn", false)
            promoText = s.optString("promoText", ""); promoCode = s.optString("promoCode", "")
            promoTerms = s.optString("promoTerms", "")
            storeMapLink = s.optString("storeMapLink", ""); storeTiming = s.optString("storeTiming", "")
            featuredArticles = s.optString("featuredArticles", "")
            lowStockOn = s.optBoolean("lowStockOn", false)
            lowStockLimit = s.optString("lowStockLimit", "3")
            sliderAuto = s.optBoolean("sliderAuto", false)
            sliderSeconds = s.optString("sliderSeconds", "5")
            heroBtnText = s.optString("heroBtnText", "Shop now"); heroBtnLink = s.optString("heroBtnLink", "")
            revealOn = s.optBoolean("revealOn", false)
            heroArticle = s.optString("heroArticle", "6102")
            bandTitle = s.optString("bandTitle", "")
            bandText = s.optString("bandText", "")
            instagram = s.optString("instagram", "")
            facebook = s.optString("facebook", "")
            whatsapp = s.optString("whatsapp", "")
            heroImage = s.optString("heroImage", "")
            heroArtImage = s.optString("heroArtImage", "")
            bandImage = s.optString("bandImage", "")
            filmVideo = s.optString("filmVideo", "")
            filmArticle = s.optString("filmArticle", "")
            slides.clear()
            s.optJSONArray("slides")?.let { arr ->
                for (k in 0 until arr.length()) {
                    val o = arr.optJSONObject(k) ?: continue
                    slides.add(SlideDraft(
                        isVideo = o.optString("kind") == "video",
                        url = o.optString("url"),
                        title = o.optString("title"),
                        text = o.optString("text"),
                        article = o.optString("article"),
                        active = o.optBoolean("active", true)
                    ))
                }
            }
            s.optJSONObject("offer")?.let { o ->
                offerOn = o.optBoolean("on", false)
                offerTitle = o.optString("title", "Today's offer")
                offerText = o.optString("text")
                offerCode = o.optString("code")
                offerKind = o.optString("kind", "percent")
                val v = o.optDouble("value", 0.0)
                offerValue = if (v <= 0.0) "" else if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
                offerCategory = if (o.isNull("category")) "" else o.optString("category")
            }
            season = s.optString("season", "")
            shipFee = r?.opt("shippingFee")?.toString()?.removeSuffix(".0") ?: ""
        } catch (e: Exception) {
            error = msg(e)
        }
        loading = false
        try {
            loadCoupons()
        } catch (e: Exception) {
            toast(ctx, "Could not load coupons: ${msg(e)}")
        }
    }

    fun saveWebsite() = scope.launch {
        val slideArr = JSONArray()
        slides.forEach { s ->
            slideArr.put(JSONObject()
                .put("kind", if (s.isVideo) "video" else "photo")
                .put("url", s.url)
                .put("title", s.title.trim())
                .put("text", s.text.trim())
                .put("article", s.article.trim())
                .put("active", s.active))
        }
        val v = JSONObject()
            .put("heroTitle", heroTitle.trim())
            .put("heroText", heroText.trim())
            .put("heroAnim", heroAnim)
            .put("maintenanceOn", maintOn)
            .put("maintenanceText", maintText.trim())
            .put("announceOn", announceOn)
            .put("announceText", announceText.trim())
            .put("freeDeliveryAbove", freeAbove.filter { it.isDigit() }.toIntOrNull() ?: 0)
            .put("trustOn", trustOn).put("trust1", trust1.trim()).put("trust2", trust2.trim()).put("trust3", trust3.trim())
            .put("promoOn", promoOn).put("promoText", promoText.trim()).put("promoCode", promoCode.trim())
            .put("promoTerms", promoTerms.trim())
            .put("storeMapLink", storeMapLink.trim()).put("storeTiming", storeTiming.trim())
            .put("featuredArticles", featuredArticles.trim())
            .put("lowStockOn", lowStockOn).put("lowStockLimit", lowStockLimit.toIntOrNull() ?: 3)
            .put("sliderAuto", sliderAuto).put("sliderSeconds", sliderSeconds.toIntOrNull() ?: 5)
            .put("heroBtnText", heroBtnText.trim()).put("heroBtnLink", heroBtnLink.trim())
            .put("revealOn", revealOn)
            .put("heroArticle", heroArticle.trim())
            .put("bandTitle", bandTitle.trim())
            .put("bandText", bandText.trim())
            .put("instagram", instagram.trim())
            .put("facebook", facebook.trim())
            .put("whatsapp", whatsapp.filter { it.isDigit() })
            .put("heroImage", heroImage)
            .put("heroArtImage", heroArtImage)
            .put("bandImage", bandImage)
            .put("filmVideo", filmVideo)
            .put("filmArticle", filmArticle.trim())
            .put("slides", slideArr)
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
            toast(ctx, "Packing charge saved.")
        } catch (e: Exception) {
            toast(ctx, "Save failed: ${msg(e)}")
        }
    }

    fun saveCoupon() = scope.launch {
        val value = cValue.trim().toDoubleOrNull()
        if (cCode.trim().length < 3) { toast(ctx, "Coupon code must be at least 3 letters or numbers."); return@launch }
        if (value == null || value <= 0 || (cKind == "percent" && value > 100)) {
            toast(ctx, if (cKind == "percent") "Percent must be between 1 and 100." else "Enter the rupee amount.")
            return@launch
        }
        val c = JSONObject().put("code", cCode.trim()).put("kind", cKind).put("value", value).put("active", true)
        if (cCategory.isNotEmpty()) c.put("category", cCategory)
        try {
            call("owner_save_coupon", JSONObject().put("p_coupon", c))
            toast(ctx, "Coupon saved.")
            cCode = ""; cValue = ""; cCategory = ""
            loadCoupons()
        } catch (e: Exception) {
            toast(ctx, "Save failed: ${msg(e)}")
        }
    }

    fun saveOffer() = scope.launch {
        val code = offerCode.trim().uppercase()
        val value = offerValue.trim().toDoubleOrNull()
        if (offerOn) {
            if (code.length < 3) { toast(ctx, "Offer code must be at least 3 letters or numbers."); return@launch }
            if (value == null || value <= 0 || (offerKind == "percent" && value > 100)) {
                toast(ctx, if (offerKind == "percent") "Percent must be between 1 and 100." else "Enter the rupee amount.")
                return@launch
            }
        }
        try {
            if (offerOn && code.length >= 3 && value != null) {
                val c = JSONObject().put("code", code).put("kind", offerKind).put("value", value).put("active", true)
                if (offerCategory.isNotEmpty()) c.put("category", offerCategory)
                call("owner_save_coupon", JSONObject().put("p_coupon", c))
            }
            val o = JSONObject()
                .put("on", offerOn)
                .put("title", offerTitle.trim())
                .put("text", offerText.trim())
                .put("code", code)
                .put("kind", offerKind)
                .put("value", value ?: 0.0)
                .put("category", offerCategory)
            call("owner_save_site_settings", JSONObject().put("p_value", JSONObject().put("offer", o)))
            toast(ctx, if (offerOn) "Offer is on. Reload the shop page to check." else "Offer popup is off.")
            if (offerOn) loadCoupons()
        } catch (e: Exception) {
            toast(ctx, "Save failed: ${msg(e)}")
        }
    }

    /** Turns one season on (others go off by themselves, because only one value is saved) or all off (""). */
    fun saveSeason(key: String) = scope.launch {
        val prev = season
        season = key
        try {
            call("owner_save_site_settings", JSONObject().put("p_value", JSONObject().put("season", key)))
            // the server keeps only the settings it knows; check that "season" was really saved
            val r = call("owner_get_site_settings", JSONObject()) as? JSONObject
            val saved = r?.optJSONObject("site")?.optString("season", "") ?: ""
            if (saved != key) {
                season = prev
                toast(ctx, "Not saved. Run owner_update_13_season.sql in Supabase SQL Editor once, then try again.")
                return@launch
            }
            val name = SEASONS.firstOrNull { it.key == key }?.hi
            toast(ctx, if (name == null) "All seasons are off." else "$name is on. Other seasons are off.")
        } catch (e: Exception) {
            season = prev
            toast(ctx, "Save failed: ${msg(e)}")
        }
    }

    fun setCouponActive(row: CouponRow, active: Boolean) = scope.launch {
        val c = JSONObject().put("code", row.code).put("kind", row.kind).put("value", row.value).put("active", active)
        if (row.category.isNotEmpty()) c.put("category", row.category)
        try {
            call("owner_save_coupon", JSONObject().put("p_coupon", c))
            loadCoupons()
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

        SectionTitle("Packing charge (website)")
        Text("Added to every website order, on top of the courier price the customer chooses. Keep 0 for no packing charge.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Field("Packing charge (0 to 5000)", shipFee, keyboard = KeyboardType.Decimal) { shipFee = it }
        Button(onClick = { saveShipping() }, enabled = ready, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text("Save packing charge")
        }

        SectionTitle("Coupons")
        Text("A coupon takes rupees or percent off the items total at checkout. The customer types the code. " +
            "Nothing is applied on its own. Turn a coupon off when you do not need it.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Field("Coupon code (3 to 20 letters or numbers, e.g. SAVE10)", cCode) { cCode = it.uppercase() }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
            FilterChip(selected = cKind == "percent", onClick = { cKind = "percent" }, label = { Text("% off") })
            FilterChip(selected = cKind == "flat", onClick = { cKind = "flat" }, label = { Text("₹ off") })
        }
        Field(if (cKind == "percent") "Percent off (1 to 100)" else "Rupees off", cValue, keyboard = KeyboardType.Decimal) { cValue = it }
        Text("Applies to", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = cCategory.isEmpty(), onClick = { cCategory = "" }, label = { Text("All categories") })
            categories.forEach { c ->
                FilterChip(selected = cCategory == c, onClick = { cCategory = c }, label = { Text(c) })
            }
        }
        Button(onClick = { saveCoupon() }, enabled = ready, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text("Save coupon")
        }
        coupons.forEach { c ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(c.code, fontWeight = FontWeight.SemiBold)
                    Text(couponLine(c), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { setCouponActive(c, !c.active) }, enabled = ready) {
                        Text(if (c.active) "Turn off" else "Turn on")
                    }
                }
            }
        }

        SectionTitle("Today's offer popup (website)")
        Text("A box that opens on the website with a coupon code. It opens once per browser, and a new offer opens again. " +
            "Turn it off when you do not need it.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(offerOn, { offerOn = it }); Spacer(Modifier.width(8.dp)); Text("Show the offer popup on the website")
        }
        Field("Heading (e.g. Today's offer)", offerTitle) { offerTitle = it }
        Field("Short text (optional)", offerText, single = false) { offerText = it }
        Field("Coupon code (3 to 20 letters or numbers, e.g. TODAY10)", offerCode) { offerCode = it.uppercase() }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
            FilterChip(selected = offerKind == "percent", onClick = { offerKind = "percent" }, label = { Text("% off") })
            FilterChip(selected = offerKind == "flat", onClick = { offerKind = "flat" }, label = { Text("₹ off") })
        }
        Field(if (offerKind == "percent") "Percent off (1 to 100)" else "Rupees off", offerValue, keyboard = KeyboardType.Decimal) { offerValue = it }
        Text("Applies to", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = offerCategory.isEmpty(), onClick = { offerCategory = "" }, label = { Text("All categories") })
            categories.forEach { c ->
                FilterChip(selected = offerCategory == c, onClick = { offerCategory = c }, label = { Text(c) })
            }
        }
        Button(onClick = { saveOffer() }, enabled = ready, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text("Save offer")
        }

        SectionTitle("Season animation (website)")
        Text("The season that is on plays for about 8 seconds when the website opens, with a soft sound. " +
            "Turn one on and the others go off by themselves. It saves as soon as you tap the switch.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SEASONS.forEachIndexed { i, s ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text("${i + 1}.", fontWeight = FontWeight.Bold, modifier = Modifier.width(30.dp))
                Column(Modifier.weight(1f)) {
                    Text("${s.hi}  (${s.en})", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(s.what, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = season == s.key, enabled = ready,
                    onCheckedChange = { on -> saveSeason(if (on) s.key else "") })
            }
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
        PhotoRow("1. Top photo or video", heroImage, uploadingSlot == "hero", ready,
            onPick = { pickPhoto("hero") }, onRemove = { heroImage = "" }, removeLabel = "Remove")
        PhotoRow("2. Top sandal photo (floating)", heroArtImage, uploadingSlot == "heroart", ready,
            onPick = { pickPhoto("heroart") }, onRemove = { heroArtImage = "" }, removeLabel = "Use default photo",
            emptyText = "Default photo is used")
        PhotoRow("3. Middle band photo or video", bandImage, uploadingSlot == "band", ready,
            onPick = { pickPhoto("band") }, onRemove = { bandImage = "" }, removeLabel = "Remove")
        PhotoRow("4. Brand film video (box above the shop)", filmVideo, uploadingSlot == "film", ready,
            onPick = { pickPhoto("film") }, onRemove = { filmVideo = "" }, removeLabel = "Remove",
            emptyText = "No video. The box says Brand film coming soon")
        Field("Product article code (click on the film opens this product, optional)", filmArticle) { filmArticle = it }

        SectionTitle("Home slider (swap screens)")
        Text("The first screen is the red top screen (it stays as it is). Add up to $MAX_SLIDES more screens below. " +
            "Each screen can have a photo or video, a heading, a short text and one product (type its article code). " +
            "The screens change by themselves and can be swiped.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        slides.forEachIndexed { i, s ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Extra screen ${i + 1}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text("Show", fontSize = 12.sp)
                        Switch(s.active, { slides[i] = slides[i].copy(active = it) })
                    }
                    Text(
                        when {
                            s.url.isBlank() -> "No photo or video (red background)"
                            s.isVideo -> "Video uploaded"
                            else -> "Photo uploaded"
                        },
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { pickPhoto("slide", i) },
                            enabled = ready && uploadingSlot == null) {
                            Text(if (uploadingSlot == "slide$i") "Uploading..." else "Choose photo or video")
                        }
                        if (s.url.isNotBlank()) {
                            TextButton(onClick = { slides[i] = slides[i].copy(url = "", isVideo = false) },
                                enabled = ready && uploadingSlot == null) { Text("Remove") }
                        }
                    }
                    Field("Heading", s.title) { slides[i] = slides[i].copy(title = it) }
                    Field("Short text", s.text, single = false) { slides[i] = slides[i].copy(text = it) }
                    Field("Product article code (optional)", s.article) { slides[i] = slides[i].copy(article = it) }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { slides.add(i - 1, slides.removeAt(i)) }, enabled = i > 0) { Text("Move up") }
                        TextButton(onClick = { slides.add(i + 1, slides.removeAt(i)) },
                            enabled = i < slides.size - 1) { Text("Move down") }
                        TextButton(onClick = { slides.removeAt(i) }) {
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
        if (slides.size < MAX_SLIDES) {
            OutlinedButton(onClick = { slides.add(SlideDraft()) }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text("Add a screen")
            }
        }

        SectionTitle("Middle band")
        Field("Band heading", bandTitle) { bandTitle = it }
        Field("Band text", bandText, single = false) { bandText = it }

        SectionTitle("Social and WhatsApp")
        Field("Instagram link", instagram) { instagram = it }
        Field("Facebook link", facebook) { facebook = it }
        Field("WhatsApp number (with country code, without +)", whatsapp, keyboard = KeyboardType.Phone) { whatsapp = it }

        SectionTitle("Website switches")
        Text("Website closed: the whole website shows only the message below until you switch it off. Use it while you test changes.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(maintOn, { maintOn = it }); Spacer(Modifier.width(8.dp)); Text("Website closed (maintenance)")
        }
        Field("Message on the closed page", maintText, single = false) { maintText = it }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(announceOn, { announceOn = it }); Spacer(Modifier.width(8.dp)); Text("Top bar on the website")
        }
        Field("Top bar text (example: ₹999 से ऊपर फ्री डिलीवरी)", announceText) { announceText = it }

        SectionTitle("Website extras (saved now; shown on the website after it is applied)")
        SwitchRow("Trust line under the top bar", trustOn) { trustOn = it }
        Field("Trust point 1 (example: 30 दिन में वापसी)", trust1) { trust1 = it }
        Field("Trust point 2 (example: सुरक्षित भुगतान)", trust2) { trust2 = it }
        Field("Trust point 3 (example: ओरिजिनल प्रोडक्ट)", trust3) { trust3 = it }
        SwitchRow("Promo banner with a coupon code", promoOn) { promoOn = it }
        Field("Promo text (example: Extra 15% off on ₹1499 and above)", promoText, single = false) { promoText = it }
        Field("Coupon code (must exist in Coupons)", promoCode) { promoCode = it.uppercase() }
        Field("Short terms (optional)", promoTerms, single = false) { promoTerms = it }
        Field("Find a store: Google Maps link", storeMapLink) { storeMapLink = it }
        Field("Shop timings (example: 10 am to 9 pm, Mon to Sat)", storeTiming) { storeTiming = it }
        Field("Featured products on home (article codes, comma separated)", featuredArticles, single = false) { featuredArticles = it }
        SwitchRow("Show 'only N left' on product cards", lowStockOn) { lowStockOn = it }
        Field("Show it when this many or fewer are left", lowStockLimit, keyboard = KeyboardType.Number) { lowStockLimit = it.filter { c -> c.isDigit() }.take(2) }
        SwitchRow("Home slider changes by itself", sliderAuto) { sliderAuto = it }
        Field("Seconds per screen", sliderSeconds, keyboard = KeyboardType.Number) { sliderSeconds = it.filter { c -> c.isDigit() }.take(2) }
        Field("Top photo button text (example: Shop now)", heroBtnText) { heroBtnText = it }
        Field("Top photo button opens (article code, optional)", heroBtnLink) { heroBtnLink = it }
        SwitchRow("Scroll animation (sections slide in)", revealOn) { revealOn = it }

        Field("Free delivery above ₹ (0 = no free delivery)", freeAbove, keyboard = KeyboardType.Number) { freeAbove = it }

        SectionTitle("Export and sync")
        Text("Download as a CSV file (opens in Excel or Google Sheets). Your data is not changed.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            OutlinedButton(onClick = { shareCsv(ctx, "orders.csv", ordersCsv(allOrders)) }, modifier = Modifier.weight(1f)) { Text("Orders") }
            OutlinedButton(onClick = { shareCsv(ctx, "shop-customers.csv", shopCustomersCsv(shopCust)) }, modifier = Modifier.weight(1f)) { Text("Shop customers") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            OutlinedButton(onClick = { shareCsv(ctx, "website-customers.csv", webCustomersCsv(webCust)) }, modifier = Modifier.weight(1f)) { Text("Website customers") }
            OutlinedButton(onClick = { app.sync.kick(); toast(ctx, "Syncing to the server...") }, modifier = Modifier.weight(1f)) { Text("Sync now") }
        }

        Button(onClick = { saveWebsite() }, enabled = ready && uploadingSlot == null,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text("Save website")
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** "10% off · All categories · used 3 · On" */
private fun couponLine(c: CouponRow): String {
    fun n(v: Double) = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
    val amount = if (c.kind == "percent") "${n(c.value)}% off" else "₹${n(c.value)} off"
    val where = if (c.category.isEmpty()) "All categories" else c.category
    return "$amount · $where · used ${c.used} · ${if (c.active) "On" else "Off"}"
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
            Text(if (url.isBlank()) emptyText else "Uploaded", fontSize = 12.sp,
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

@Composable
private fun SwitchRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(on, onChange); Spacer(Modifier.width(8.dp)); Text(label)
    }
}
