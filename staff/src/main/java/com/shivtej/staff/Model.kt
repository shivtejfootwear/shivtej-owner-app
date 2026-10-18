package com.shivtej.staff

import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale

data class SSize(val size: String, val inStock: Boolean)
data class SColour(val colour: String, val images: List<String>, val sizes: List<SSize>)
data class SProduct(
    val id: String,
    val article: String,
    val name: String,
    val brand: String,
    val category: String,
    val type: String,
    val mrp: Double,
    val isNew: Boolean,
    val description: String,
    val images: List<String>,
    val colours: List<SColour>
) {
    val cats: List<String> = category.split(",").map { c ->
        c.trim().lowercase().split(" ").filter { it.isNotEmpty() }.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    }.filter { it.isNotEmpty() }
    val title: String get() = article.ifBlank { name }
}

private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { i -> optString(i, "").takeIf { it.isNotBlank() } }

fun parseCatalog(a: JSONArray): List<SProduct> = (0 until a.length()).mapNotNull { i ->
    val o = a.optJSONObject(i) ?: return@mapNotNull null
    val cols = o.optJSONArray("colours") ?: JSONArray()
    SProduct(
        id = o.optString("id"), article = o.optString("article"), name = o.optString("name"),
        brand = o.optString("brand").trim(), category = o.optString("category"), type = o.optString("type").trim(),
        mrp = o.optDouble("mrp", 0.0), isNew = o.optBoolean("new", false), description = o.optString("description"),
        images = o.optJSONArray("images").strings(),
        colours = (0 until cols.length()).mapNotNull { j ->
            val c: JSONObject = cols.optJSONObject(j) ?: return@mapNotNull null
            val sz = c.optJSONArray("sizes") ?: JSONArray()
            SColour(
                c.optString("colour").trim(), c.optJSONArray("images").strings(),
                (0 until sz.length()).mapNotNull { k ->
                    val s = sz.optJSONObject(k) ?: return@mapNotNull null
                    SSize(s.optString("size"), s.optBoolean("in", false))
                }
            )
        }
    )
}

private val inr: NumberFormat = NumberFormat.getNumberInstance(Locale("en", "IN")).apply { maximumFractionDigits = 2 }
fun money(v: Double): String = "₹" + inr.format(v)
