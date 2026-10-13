package com.shivtej.owner.data

import org.json.JSONObject

/**
 * The shop and bill details that are kept on the server too (table owner_shop_settings), so a new phone or a
 * reinstall gets them back without typing them again. The shop logo and the bill theme are not sent (they are files).
 */
object ShopSync {
    fun payload(p: Prefs): JSONObject = JSONObject()
        .put("name", p.storeName).put("tagline", p.shopTagline).put("address", p.storeAddress)
        .put("phone", p.storePhone).put("email", p.shopEmail).put("website", p.shopWebsite)
        .put("instagram", p.shopInstagram).put("whatsapp", p.shopWhatsapp).put("gstin", p.shopGstin)
        .put("tax_percent", p.taxPercent).put("thanks", p.billThanks).put("footer", p.billFooter)
        .put("notes", p.billNotes)

    /** Writes the server copy into this phone's settings. A key the server does not have keeps the phone's value. */
    fun apply(p: Prefs, d: JSONObject, updatedMs: Long) {
        fun text(k: String, cur: String): String = if (d.has(k) && !d.isNull(k)) d.optString(k, "") else cur
        p.storeName = text("name", p.storeName).ifBlank { "Shivtej Footwear" }
        p.shopTagline = text("tagline", p.shopTagline)
        p.storeAddress = text("address", p.storeAddress)
        p.storePhone = text("phone", p.storePhone)
        p.shopEmail = text("email", p.shopEmail)
        p.shopWebsite = text("website", p.shopWebsite)
        p.shopInstagram = text("instagram", p.shopInstagram)
        p.shopWhatsapp = text("whatsapp", p.shopWhatsapp)
        p.shopGstin = text("gstin", p.shopGstin)
        p.taxPercent = if (d.has("tax_percent") && !d.isNull("tax_percent")) d.optDouble("tax_percent", 0.0) else p.taxPercent
        p.billThanks = text("thanks", p.billThanks)
        p.billFooter = text("footer", p.billFooter)
        p.billNotes = text("notes", p.billNotes)
        p.shopEditedAt = updatedMs
    }
}
