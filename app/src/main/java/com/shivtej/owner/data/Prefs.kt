package com.shivtej.owner.data

import android.content.Context
import org.json.JSONObject

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("owner_prefs", Context.MODE_PRIVATE)

    var ownerCode: String
        get() = sp.getString("owner_code", "") ?: ""
        set(v) = sp.edit().putString("owner_code", v).apply()

    var appPin: String   // optional local lock PIN
        get() = sp.getString("app_pin", "") ?: ""
        set(v) = sp.edit().putString("app_pin", v).apply()

    var storeName: String
        get() = sp.getString("store_name", "Shivtej Footwear") ?: "Shivtej Footwear"
        set(v) = sp.edit().putString("store_name", v).apply()
    var storeAddress: String
        get() = sp.getString("store_address", "") ?: ""
        set(v) = sp.edit().putString("store_address", v).apply()
    var storePhone: String
        get() = sp.getString("store_phone", "") ?: ""
        set(v) = sp.edit().putString("store_phone", v).apply()
    var currency: String
        get() = sp.getString("currency", "₹") ?: "₹"
        set(v) = sp.edit().putString("currency", v).apply()
    var lowThreshold: Int
        get() = sp.getInt("low_threshold", 2)
        set(v) = sp.edit().putInt("low_threshold", v).apply()
    var barcodePrefix: String
        get() = sp.getString("barcode_prefix", "SW") ?: "SW"
        set(v) = sp.edit().putString("barcode_prefix", v).apply()
    var printerNote: String
        get() = sp.getString("printer_note", "") ?: ""
        set(v) = sp.edit().putString("printer_note", v).apply()
    var lastSync: Long
        get() = sp.getLong("last_sync", 0L)
        set(v) = sp.edit().putLong("last_sync", v).apply()
    var lastLogId: Long
        get() = sp.getLong("last_log_id", 0L)
        set(v) = sp.edit().putLong("last_log_id", v).apply()
    var draftCart: String
        get() = sp.getString("draft_cart", "[]") ?: "[]"
        set(v) = sp.edit().putString("draft_cart", v).apply()
    var billCounter: Int
        get() = sp.getInt("bill_counter", 0)
        set(v) = sp.edit().putInt("bill_counter", v).apply()

    // local image path -> uploaded public URL
    fun uploadedUrl(path: String): String? =
        try { JSONObject(sp.getString("uploaded", "{}") ?: "{}").optString(path).ifBlank { null } }
        catch (e: Exception) { null }

    fun setUploaded(path: String, url: String) {
        val o = try { JSONObject(sp.getString("uploaded", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        o.put(path, url)
        sp.edit().putString("uploaded", o.toString()).apply()
    }

    fun resetSyncState() {
        sp.edit().remove("last_log_id").remove("last_sync").remove("uploaded").apply()
    }
}
