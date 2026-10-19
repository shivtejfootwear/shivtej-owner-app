package com.shivtej.owner.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/**
 * What is typed on the bill besides the items (customer, sales person, payment ...).
 * Text fields stay text so a half-typed number is never lost; "" means "use the automatic value".
 */
data class BillDraft(
    val customerMobile: String = "",
    val customerName: String = "",
    val customerAddress: String = "",
    val customerGender: String = "",     // "M" / "F" / "" (not chosen)
    val salesPersonId: String = "",
    val salesPersonName: String = "",
    val method: String = "Cash",
    val taxText: String = "",        // "" = the GST % saved in Shop settings
    val paidText: String = ""        // "" = the customer pays the full grand total
)

/** Current POS bill. Saved locally on every change, so a draft bill survives app restarts. */
class CartStore(private val prefs: Prefs) {
    val lines = mutableStateListOf<CartLine>()
    var draft by mutableStateOf(BillDraft())
        private set

    // When editing a saved bill, these hold which bill (same id / number); "" = a new bill.
    var editingBillId by mutableStateOf(prefs.editingBillId)
        private set
    var editingBillNo by mutableStateOf(prefs.editingBillNo)
        private set
    // When this bill came from a manager's "Staff Bill", this holds that pending bill's id ("" = normal bill).
    var fromPendingId by mutableStateOf(prefs.fromPendingId)
        private set

    /** Start a NEW bill from a manager's sent items (the owner then adds the customer and rates). */
    fun startFromPending(pendingId: String, newLines: List<CartLine>) {
        lines.clear(); lines.addAll(newLines); save()
        editingBillId = ""; editingBillNo = ""; prefs.editingBillId = ""; prefs.editingBillNo = ""
        fromPendingId = pendingId; prefs.fromPendingId = pendingId
        updateDraft { BillDraft(salesPersonId = it.salesPersonId, salesPersonName = it.salesPersonName, method = it.method) }
    }

    /** Re-open a saved bill for editing: its items and customer details fill the cart, keeping its number. */
    fun startEditing(billId: String, billNo: String, newLines: List<CartLine>, d: BillDraft) {
        lines.clear(); lines.addAll(newLines); save()
        editingBillId = billId; editingBillNo = billNo
        prefs.editingBillId = billId; prefs.editingBillNo = billNo
        draft = d; saveDraft()
    }

    private fun stopEditing() {
        editingBillId = ""; editingBillNo = ""; fromPendingId = ""
        prefs.editingBillId = ""; prefs.editingBillNo = ""; prefs.fromPendingId = ""
    }

    init {
        try {
            val a = JSONArray(prefs.draftCart)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                lines.add(CartLine(o.str("productId"), o.str("article"), o.str("name"), o.str("colour"),
                    o.str("size"), o.str("barcode"), o.optInt("qty", 1), o.optDouble("price", 0.0), o.optInt("maxQty", 1),
                    o.optDouble("mrp", 0.0)))
            }
        } catch (_: Exception) { }
        try {
            if (prefs.draftMeta.isNotBlank()) {
                val o = JSONObject(prefs.draftMeta)
                draft = BillDraft(
                    o.str("customerMobile"), o.str("customerName"), o.str("customerAddress"), o.str("customerGender"),
                    o.str("salesPersonId"), o.str("salesPersonName"),
                    o.str("method").ifBlank { "Cash" }, o.str("taxText"), o.str("paidText")
                )
            }
        } catch (_: Exception) { }
        if (draft.salesPersonId.isBlank() && prefs.lastSalesPersonId.isNotBlank()) {
            draft = draft.copy(salesPersonId = prefs.lastSalesPersonId)
        }
    }

    private fun save() {
        val a = JSONArray()
        lines.forEach {
            a.put(JSONObject().put("productId", it.productId).put("article", it.article).put("name", it.name)
                .put("colour", it.colour).put("size", it.size).put("barcode", it.barcode)
                .put("qty", it.qty).put("price", it.price).put("maxQty", it.maxQty).put("mrp", it.mrp))
        }
        prefs.draftCart = a.toString()
    }

    private fun saveDraft() {
        val d = draft
        prefs.draftMeta = JSONObject().put("customerMobile", d.customerMobile).put("customerName", d.customerName)
            .put("customerAddress", d.customerAddress).put("customerGender", d.customerGender).put("salesPersonId", d.salesPersonId)
            .put("salesPersonName", d.salesPersonName).put("method", d.method)
            .put("taxText", d.taxText).put("paidText", d.paidText).toString()
    }

    fun updateDraft(change: (BillDraft) -> BillDraft) {
        draft = change(draft)
        saveDraft()
    }

    /** Sum of rate x quantity (before any tax). */
    val total: Double get() = lines.sumOf { it.total }

    /** Returns an error message, or null when added. */
    fun add(line: CartLine): String? {
        if (line.maxQty <= 0) return "Out of stock"
        val i = lines.indexOfFirst { it.productId == line.productId && it.colour == line.colour && it.size == line.size }
        if (i >= 0) {
            val cur = lines[i]
            val q = cur.qty + line.qty
            if (q > line.maxQty) return "Only ${line.maxQty} in stock"
            // the selling rate the owner already typed for this item is kept.
            // Every scanned piece keeps its own barcode on the line (comma-separated), so each one leaves the stock on sale.
            val codes = (cur.barcode.split(',') + line.barcode.split(','))
                .map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(",")
            lines[i] = cur.copy(qty = q, maxQty = line.maxQty, barcode = codes,
                mrp = if (line.mrp > 0.0) line.mrp else cur.mrp)
        } else {
            if (line.qty > line.maxQty) return "Only ${line.maxQty} in stock"
            lines.add(line)
        }
        save()
        return null
    }

    fun change(i: Int, delta: Int): String? {
        val cur = lines.getOrNull(i) ?: return null
        val q = cur.qty + delta
        if (q < 1) return null
        if (q > cur.maxQty) return "Only ${cur.maxQty} in stock"
        lines[i] = cur.copy(qty = q)
        save()
        return null
    }

    /** The owner types a new selling rate. The MRP stays; the discount is worked out from the two. */
    fun setPrice(i: Int, price: Double) {
        val cur = lines.getOrNull(i) ?: return
        if (price < 0.0 || price.isNaN() || price.isInfinite()) return
        lines[i] = cur.copy(price = price)
        save()
    }

    fun remove(i: Int) { if (i in lines.indices) { lines.removeAt(i); save() } }

    /** Empties the bill after it is saved or cleared. The sales person and the payment mode are kept for the next bill. */
    fun clear() {
        lines.clear()
        save()
        stopEditing()
        updateDraft { BillDraft(salesPersonId = it.salesPersonId, salesPersonName = it.salesPersonName, method = it.method) }
    }
}
