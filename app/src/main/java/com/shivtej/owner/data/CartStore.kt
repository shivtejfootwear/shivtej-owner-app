package com.shivtej.owner.data

import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject

/** Current POS bill. Saved locally on every change, so a draft bill survives app restarts. */
class CartStore(private val prefs: Prefs) {
    val lines = mutableStateListOf<CartLine>()

    init {
        try {
            val a = JSONArray(prefs.draftCart)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                lines.add(CartLine(o.str("productId"), o.str("article"), o.str("name"), o.str("colour"),
                    o.str("size"), o.str("barcode"), o.optInt("qty", 1), o.optDouble("price", 0.0), o.optInt("maxQty", 1)))
            }
        } catch (_: Exception) { }
    }

    private fun save() {
        val a = JSONArray()
        lines.forEach {
            a.put(JSONObject().put("productId", it.productId).put("article", it.article).put("name", it.name)
                .put("colour", it.colour).put("size", it.size).put("barcode", it.barcode)
                .put("qty", it.qty).put("price", it.price).put("maxQty", it.maxQty))
        }
        prefs.draftCart = a.toString()
    }

    val total: Double get() = lines.sumOf { it.total }

    /** Returns an error message, or null when added. */
    fun add(line: CartLine): String? {
        if (line.maxQty <= 0) return "Out of stock"
        val i = lines.indexOfFirst { it.productId == line.productId && it.colour == line.colour && it.size == line.size }
        if (i >= 0) {
            val cur = lines[i]
            val q = cur.qty + line.qty
            if (q > line.maxQty) return "Only ${line.maxQty} in stock"
            lines[i] = cur.copy(qty = q, maxQty = line.maxQty, price = line.price)
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

    fun remove(i: Int) { if (i in lines.indices) { lines.removeAt(i); save() } }
    fun clear() { lines.clear(); save() }
}
