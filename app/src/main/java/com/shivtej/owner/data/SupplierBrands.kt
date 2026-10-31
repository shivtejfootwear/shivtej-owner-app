package com.shivtej.owner.data

import org.json.JSONArray
import org.json.JSONObject

/** One brand a supplier gives, with the margin % the supplier gives on it (items get MRP minus this margin). */
data class SupplierBrand(val brand: String, val margin: Double = 0.0)

fun parseSupplierBrands(json: String): List<SupplierBrand> = runCatching {
    val a = JSONArray(json)
    (0 until a.length()).map { i ->
        val o = a.getJSONObject(i)
        SupplierBrand(o.optString("brand").trim().uppercase(), o.optDouble("margin", 0.0))
    }.filter { it.brand.isNotEmpty() }
}.getOrDefault(emptyList())

fun supplierBrandsArray(list: List<SupplierBrand>): JSONArray {
    val a = JSONArray()
    list.forEach { a.put(JSONObject().put("brand", it.brand).put("margin", it.margin)) }
    return a
}

fun supplierBrandsJson(list: List<SupplierBrand>): String = supplierBrandsArray(list).toString()

/** The purchase rate of an item: MRP minus the brand margin, to the paisa. */
fun purchaseRateFor(mrp: Double, margin: Double): Double =
    com.shivtej.owner.util.BillMath.round2(mrp * (1.0 - margin / 100.0))
