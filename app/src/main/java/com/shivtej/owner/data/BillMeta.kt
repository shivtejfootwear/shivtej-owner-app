package com.shivtej.owner.data

import com.shivtej.owner.util.BillMath
import com.shivtej.owner.util.BillTheme
import com.shivtej.owner.util.RLine
import com.shivtej.owner.util.ReceiptData
import com.shivtej.owner.util.ShopInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Shop details + bill design stored INSIDE every bill ("meta"), so a bill always prints the way it was made,
 * however the shop settings or the theme change later.
 */
object BillMeta {
    // ------------------------------------------------------------------ shop
    fun shopToJson(s: ShopInfo): JSONObject = JSONObject()
        .put("name", s.name).put("tagline", s.tagline).put("address", s.address).put("phone", s.phone)
        .put("email", s.email).put("website", s.website).put("instagram", s.instagram)
        .put("whatsapp", s.whatsapp).put("gstin", s.gstin).put("footer", s.footer).put("thanks", s.thanks)
        .put("notes", JSONArray(s.notes)).put("logo", s.logoPath).put("currency", s.currency)

    fun shopFromJson(o: JSONObject): ShopInfo {
        val notes = ArrayList<String>()
        val a = o.optJSONArray("notes")
        if (a != null) for (i in 0 until a.length()) a.optString(i).trim().takeIf { it.isNotEmpty() }?.let { notes.add(it) }
        return ShopInfo(
            name = o.str("name").ifBlank { "Shivtej Footwear" }, tagline = o.str("tagline"), address = o.str("address"),
            phone = o.str("phone"), email = o.str("email"), website = o.str("website"), instagram = o.str("instagram"),
            whatsapp = o.str("whatsapp"), gstin = o.str("gstin"), footer = o.str("footer"), thanks = o.str("thanks"),
            notes = notes, logoPath = o.str("logo"), currency = o.str("currency").ifBlank { "₹" }
        )
    }

    // ------------------------------------------------------------------ theme
    fun themeToJson(t: BillTheme): JSONObject = JSONObject()
        .put("id", t.id).put("accent", t.accent).put("accent2", t.accent2).put("tint", t.tint)
        .put("bgStyle", t.bgStyle).put("bgImage", t.bgImage).put("bgStrength", t.bgStrength)
        .put("showLogo", t.showLogo).put("showTagline", t.showTagline).put("showQr", t.showQr)
        .put("showInstagramQr", t.showInstagramQr).put("centerHeader", t.centerHeader)
        .put("showNotes", t.showNotes).put("showThanks", t.showThanks).put("showFooter", t.showFooter)

    fun themeFromJson(o: JSONObject): BillTheme {
        val d = BillTheme()
        return BillTheme(
            id = o.str("id").ifBlank { d.id },
            accent = o.optInt("accent", d.accent), accent2 = o.optInt("accent2", d.accent2), tint = o.optInt("tint", d.tint),
            bgStyle = o.str("bgStyle").ifBlank { d.bgStyle }, bgImage = o.str("bgImage"),
            bgStrength = o.optInt("bgStrength", d.bgStrength),
            showLogo = o.optBoolean("showLogo", d.showLogo), showTagline = o.optBoolean("showTagline", d.showTagline),
            showQr = o.optBoolean("showQr", d.showQr), showInstagramQr = o.optBoolean("showInstagramQr", d.showInstagramQr),
            centerHeader = o.optBoolean("centerHeader", d.centerHeader), showNotes = o.optBoolean("showNotes", d.showNotes),
            showThanks = o.optBoolean("showThanks", d.showThanks), showFooter = o.optBoolean("showFooter", d.showFooter)
        )
    }

    // ------------------------------------------------------------------ what is saved today
    fun currentShop(p: Prefs): ShopInfo = ShopInfo(
        name = p.storeName.ifBlank { "Shivtej Footwear" }, tagline = p.shopTagline, address = p.storeAddress, phone = p.storePhone,
        email = p.shopEmail, website = p.shopWebsite, instagram = p.shopInstagram, whatsapp = p.shopWhatsapp, gstin = p.shopGstin,
        footer = p.billFooter, thanks = p.billThanks,
        notes = p.billNotes.split("\n").map { it.trim() }.filter { it.isNotEmpty() },
        logoPath = p.shopLogoPath, currency = p.currency.ifBlank { "₹" }
    )

    fun currentTheme(p: Prefs): BillTheme =
        try { if (p.billThemeJson.isBlank()) BillTheme() else themeFromJson(JSONObject(p.billThemeJson)) } catch (e: Exception) { BillTheme() }

    fun saveTheme(p: Prefs, t: BillTheme) { p.billThemeJson = themeToJson(t).toString() }

    /** The text stored in a new bill. */
    fun snapshot(p: Prefs): String =
        JSONObject().put("shop", shopToJson(currentShop(p))).put("theme", themeToJson(currentTheme(p))).toString()

    /**
     * Bills made before this update were saved without shop details. They are shown with the name / address / phone
     * that were set when the app was updated (taken once, then never changed) and a plain design.
     */
    fun rememberLegacy(p: Prefs) {
        if (p.legacyShopJson.isNotBlank()) return
        val s = ShopInfo(
            name = p.storeName.ifBlank { "Shivtej Footwear" }, tagline = "", address = p.storeAddress, phone = p.storePhone,
            thanks = "Thank you! Visit again.", currency = p.currency.ifBlank { "₹" }
        )
        p.legacyShopJson = shopToJson(s).toString()
    }

    private fun legacyShop(p: Prefs): ShopInfo =
        try { shopFromJson(JSONObject(p.legacyShopJson)) } catch (e: Exception) { currentShop(p).copy(tagline = "", notes = emptyList(), footer = "") }

    private val LEGACY_THEME = BillTheme(showTagline = false, showQr = false, showInstagramQr = false, showNotes = false, showFooter = false)

    // ------------------------------------------------------------------ saved bill -> what is printed
    fun buildReceipt(p: Prefs, bill: BillE): Pair<ReceiptData, BillTheme> {
        val meta = try { if (bill.metaJson.isBlank()) null else JSONObject(bill.metaJson) } catch (e: Exception) { null }
        val shopObj = meta?.optJSONObject("shop")
        val themeObj = meta?.optJSONObject("theme")
        val shop = if (shopObj != null) shopFromJson(shopObj) else legacyShop(p)
        val theme = if (themeObj != null) themeFromJson(themeObj) else LEGACY_THEME

        val items = parseBillItems(bill.itemsJson)
        val lines = items.mapIndexed { i, it ->
            RLine(i + 1, it.article, it.name, it.colour, it.size, it.qty, if (it.mrp > 0.0) it.mrp else it.price, it.price)
        }
        val newStyle = bill.totalMrp > 0.0 || bill.subTotal > 0.0
        val sub = if (newStyle) bill.subTotal else BillMath.round2(items.sumOf { it.price * it.qty })
        val mrp = if (newStyle) bill.totalMrp else sub
        val disc = if (newStyle) bill.totalDiscount else 0.0
        val tax = if (newStyle) bill.taxAmount else 0.0
        val data = ReceiptData(
            shop = shop, billNo = bill.billNo, dateMillis = bill.createdAt,
            customerName = bill.customerName, customerMobile = bill.customerMobile, customerAddress = bill.customerAddress,
            salesPerson = bill.salesPerson, lines = lines,
            totalMrp = mrp, totalDiscount = disc, subTotal = sub, taxPercent = bill.taxPercent, taxAmount = tax,
            grandTotal = bill.total, method = bill.method, amountPaid = bill.paid,
            balance = if (bill.amountPaid < 0.0) 0.0 else bill.balance
        )
        return data to theme
    }
}
