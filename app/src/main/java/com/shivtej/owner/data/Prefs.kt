package com.shivtej.owner.data

import android.content.Context
import org.json.JSONObject

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("owner_prefs", Context.MODE_PRIVATE)

    companion object {
        /** The Quick actions shown on the dashboard when the owner has not changed them (ids: see ui/QuickActions.kt). */
        const val DEFAULT_QUICK_ACTIONS = "bill,add_customer,add_product,staff,customers,reports,returns,printer,catalog,staff_bills"
        /** The product categories shown in the Category dropdown when the owner has not changed their order. */
        const val DEFAULT_CATEGORIES = "MEN,LADIES,KIDS,BOY,GIRL,SENIOR MEN,SENIOR WOMEN"
        const val DEFAULT_NOTES = "Thank you for shopping with Shivtej Footwear.\n" +
            "No return or exchange on used footwear.\n" +
            "For any issues, please contact within 7 days with original bill.\n" +
            "Keep this bill for warranty / exchange reference."
    }

    var ownerCode: String
        get() = sp.getString("owner_code", "") ?: ""
        set(v) = sp.edit().putString("owner_code", v).apply()

    var appPin: String   // optional local lock PIN
        get() = sp.getString("app_pin", "") ?: ""
        set(v) = sp.edit().putString("app_pin", v).apply()
    var lastBackgroundAt: Long   // when the app last went to the background (for the one-day auto lock)
        get() = sp.getLong("last_background_at", 0L)
        set(v) = sp.edit().putLong("last_background_at", v).apply()

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

    // ---- shop / bill settings (update 2) --------------------------------------------------------
    // storeName / storeAddress / storePhone above are the shop name, address and mobile number.
    var shopTagline: String
        get() = sp.getString("shop_tagline", "FOOTWEAR FOR EVERY STEP") ?: ""
        set(v) = sp.edit().putString("shop_tagline", v).apply()
    var shopEmail: String
        get() = sp.getString("shop_email", "") ?: ""
        set(v) = sp.edit().putString("shop_email", v).apply()
    var shopWebsite: String
        get() = sp.getString("shop_website", "") ?: ""
        set(v) = sp.edit().putString("shop_website", v).apply()
    var shopInstagram: String
        get() = sp.getString("shop_instagram", "") ?: ""
        set(v) = sp.edit().putString("shop_instagram", v).apply()
    var shopWhatsapp: String
        get() = sp.getString("shop_whatsapp", "") ?: ""
        set(v) = sp.edit().putString("shop_whatsapp", v).apply()
    var shopGstin: String
        get() = sp.getString("shop_gstin", "") ?: ""
        set(v) = sp.edit().putString("shop_gstin", v).apply()
    var shopLogoPath: String            // empty = the Shivtej Footwear logo that is built into the app
        get() = sp.getString("shop_logo_path", "") ?: ""
        set(v) = sp.edit().putString("shop_logo_path", v).apply()
    var billThanks: String
        get() = sp.getString("bill_thanks", "Thank you!") ?: ""
        set(v) = sp.edit().putString("bill_thanks", v).apply()
    var billFooter: String
        get() = sp.getString("bill_footer", "") ?: ""
        set(v) = sp.edit().putString("bill_footer", v).apply()
    var billNotes: String               // one note per line
        get() = sp.getString("bill_notes", DEFAULT_NOTES) ?: ""
        set(v) = sp.edit().putString("bill_notes", v).apply()
    var taxPercent: Double              // GST % added on the bill (0 = no tax line)
        get() = (sp.getString("tax_percent", "0") ?: "0").toDoubleOrNull() ?: 0.0
        set(v) = sp.edit().putString("tax_percent", v.toString()).apply()
    var billThemeJson: String           // the default bill design (empty = built-in Shivtej Red)
        get() = sp.getString("bill_theme", "") ?: ""
        set(v) = sp.edit().putString("bill_theme", v).apply()
    /** Shop details of bills made BEFORE update 2 (they were saved without any); taken once, so they never change afterwards. */
    var legacyShopJson: String
        get() = sp.getString("legacy_shop", "") ?: ""
        set(v) = sp.edit().putString("legacy_shop", v).apply()
    // update 8: the shop details are also kept on the server. shopEditedAt = when they were last changed (or taken from the server);
    // the backup fields remember the last day and app version the details were sent to the server.
    var shopEditedAt: Long
        get() = sp.getLong("shop_edited_at", 0L)
        set(v) = sp.edit().putLong("shop_edited_at", v).apply()
    var shopBackupDay: String
        get() = sp.getString("shop_backup_day", "") ?: ""
        set(v) = sp.edit().putString("shop_backup_day", v).apply()
    var shopBackupVersion: Int
        get() = sp.getInt("shop_backup_version", 0)
        set(v) = sp.edit().putInt("shop_backup_version", v).apply()

    // ---- billing screen memory
    var lastSalesPersonId: String
        get() = sp.getString("last_sales_person", "") ?: ""
        set(v) = sp.edit().putString("last_sales_person", v).apply()
    var draftMeta: String               // customer / sales person / payment typed on the bill in progress
        get() = sp.getString("draft_meta", "") ?: ""
        set(v) = sp.edit().putString("draft_meta", v).apply()
    // When set, the bill in progress is an EDIT of this saved bill (same id and number); "" = a new bill.
    var editingBillId: String
        get() = sp.getString("editing_bill_id", "") ?: ""
        set(v) = sp.edit().putString("editing_bill_id", v).apply()
    var editingBillNo: String
        get() = sp.getString("editing_bill_no", "") ?: ""
        set(v) = sp.edit().putString("editing_bill_no", v).apply()
    // When set, the bill in progress came from a manager's "Staff Bill"; completing it marks that pending bill done.
    var fromPendingId: String
        get() = sp.getString("from_pending_id", "") ?: ""
        set(v) = sp.edit().putString("from_pending_id", v).apply()

    // ---- Bluetooth receipt printer
    var printerAddress: String
        get() = sp.getString("printer_address", "") ?: ""
        set(v) = sp.edit().putString("printer_address", v).apply()
    var printerName: String
        get() = sp.getString("printer_name", "") ?: ""
        set(v) = sp.edit().putString("printer_name", v).apply()
    var printMode: String               // "graphic" = full bill picture (logo, QR, any language) / "text" = plain text
        get() = sp.getString("print_mode", "graphic") ?: "graphic"
        set(v) = sp.edit().putString("print_mode", v).apply()

    // ---- appearance (update 4): theme, accent colour, wallpaper
    var themeMode: String               // "system" (default, as before) / "light" / "dark" / "redwhite" / "custom"
        get() = sp.getString("theme_mode", "system") ?: "system"
        set(v) = sp.edit().putString("theme_mode", v).apply()
    var accentArgb: Int                 // 0 = the default Shivtej red
        get() = sp.getInt("accent_argb", 0)
        set(v) = sp.edit().putInt("accent_argb", v).apply()
    var wallKind: String                // "none" / "builtin" / "gallery"
        get() = sp.getString("wall_kind", "none") ?: "none"
        set(v) = sp.edit().putString("wall_kind", v).apply()
    var wallBuiltin: String             // id of a built-in wallpaper (see ui/Theme.kt)
        get() = sp.getString("wall_builtin", "sunset") ?: "sunset"
        set(v) = sp.edit().putString("wall_builtin", v).apply()
    var wallPath: String                // a wallpaper picked from the gallery (copied into app storage)
        get() = sp.getString("wall_path", "") ?: ""
        set(v) = sp.edit().putString("wall_path", v).apply()
    var wallFit: String                 // "crop" = fill the screen (cut the edges) / "fit" = the whole picture, no cut
        get() = sp.getString("wall_fit", "crop") ?: "crop"
        set(v) = sp.edit().putString("wall_fit", v).apply()
    var customBgArgb: Int               // background colour of the "custom" theme (white by default)
        get() = sp.getInt("custom_bg", -1)
        set(v) = sp.edit().putInt("custom_bg", v).apply()
    var categoryList: String            // product categories in the dropdown order, as comma separated names (permanent)
        get() = sp.getString("category_list", DEFAULT_CATEGORIES) ?: DEFAULT_CATEGORIES
        set(v) = sp.edit().putString("category_list", v).apply()
    var quickActions: String            // the dashboard Quick actions, in order, as comma separated ids
        get() {
            val v = sp.getString("quick_actions", DEFAULT_QUICK_ACTIONS) ?: DEFAULT_QUICK_ACTIONS
            // Catalog is new: put it once on a dashboard that was arranged before (it can be removed after that).
            var v2 = v
            if (!sp.getBoolean("qa_catalog_added", false)) {
                v2 = if (v2.split(",").any { it.trim() == "catalog" }) v2 else if (v2.isBlank()) "catalog" else "$v2,catalog"
                sp.edit().putString("quick_actions", v2).putBoolean("qa_catalog_added", true).apply()
            }
            if (!sp.getBoolean("qa_staffbills_added", false)) {
                v2 = if (v2.split(",").any { it.trim() == "staff_bills" }) v2 else if (v2.isBlank()) "staff_bills" else "$v2,staff_bills"
                sp.edit().putString("quick_actions", v2).putBoolean("qa_staffbills_added", true).apply()
            }
            return v2
        }
        set(v) = sp.edit().putString("quick_actions", v).apply()
    var wallScope: String               // "dashboard" = only the dashboard / "app" = every main screen
        get() = sp.getString("wall_scope", "dashboard") ?: "dashboard"
        set(v) = sp.edit().putString("wall_scope", v).apply()
    var wallOverlay: Int                // 0..90 % cover over the wallpaper so text stays readable
        get() = sp.getInt("wall_overlay", 60)
        set(v) = sp.edit().putInt("wall_overlay", v).apply()

    // ---- AI assistant (update 4): optional online mode. The API key is NOT stored here (see util/SecretStore.kt).
    var aiOnline: Boolean               // true = use the API when the phone is online (falls back to offline answers)
        get() = sp.getBoolean("ai_online", false)
        set(v) = sp.edit().putBoolean("ai_online", v).apply()
    var aiModel: String
        get() = sp.getString("ai_model", "claude-haiku-5-5") ?: "claude-haiku-5-5"
        set(v) = sp.edit().putString("ai_model", v).apply()

    // last known size of the private B2 bucket (set by the storage card). New files go to B2 only while it is below the switch limit.
    var b2UsedBytes: Long
        get() = sp.getLong("b2_used_bytes", 0L)
        set(v) = sp.edit().putLong("b2_used_bytes", v).apply()

    // ---- size groups (update 21): the "+ Custom" and "+ Extra size" groups, as JSON. "" = never changed (the built-in groups are listed).
    var sizePresetsJson: String
        get() = sp.getString("size_presets", "") ?: ""
        set(v) = sp.edit().putString("size_presets", v).apply()
    var sizePresetsEditedAt: Long       // when the groups were last changed here (or taken from the server)
        get() = sp.getLong("size_presets_edited_at", 0L)
        set(v) = sp.edit().putLong("size_presets_edited_at", v).apply()

    // ---- categories under product types (update 24): the product types with their categories, as JSON. "" = never changed (built-in list).
    var categoryTreeJson: String
        get() = sp.getString("category_tree", "") ?: ""
        set(v) = sp.edit().putString("category_tree", v).apply()
    var categoryMergedV1: Boolean
        get() = sp.getBoolean("category_merged_v1", false)
        set(v) = sp.edit().putBoolean("category_merged_v1", v).apply()
    var categoryTreeEditedAt: Long
        get() = sp.getLong("category_tree_edited_at", 0L)
        set(v) = sp.edit().putLong("category_tree_edited_at", v).apply()

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
