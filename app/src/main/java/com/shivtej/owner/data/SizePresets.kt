package com.shivtej.owner.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A group of sizes the owner keeps for re-use (update 21).
 * kind: "normal" = a "+ Custom" group, "small" / "big" = a "+ Extra size" group.
 * mrp: the MRP for these sizes (0 = not set, the product MRP is used).
 * Kept on this phone and on the server (table owner_size_presets), so a new phone gets them back.
 */
data class SizePreset(val id: String, val kind: String, val label: String, val sizes: List<String>, val mrp: Double = 0.0)

object SizePresets {
    const val NORMAL = "normal"
    const val SMALL = "small"
    const val BIG = "big"

    /** The four groups the app started with. Listed until the owner changes the list. */
    fun defaults(): List<SizePreset> = listOf(
        SizePreset("default-men", NORMAL, "Men 6-10", listOf("6", "7", "8", "9", "10")),
        SizePreset("default-ladies", NORMAL, "Ladies 36-40", listOf("36", "37", "38", "39", "40")),
        SizePreset("default-kids", NORMAL, "Kids 11-3", listOf("11", "12", "13", "1", "2", "3")),
        SizePreset("default-small-kids", NORMAL, "Small kids 15-20", listOf("15", "16", "17", "18", "19", "20"))
    )

    fun parse(json: String): List<SizePreset> = try {
        val a = JSONObject(json).optJSONArray("presets") ?: JSONArray()
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val sa = o.optJSONArray("sizes") ?: JSONArray()
            val sizes = (0 until sa.length()).map { sa.optString(it).trim() }.filter { it.isNotEmpty() }
            if (sizes.isEmpty()) null
            else {
                val m = o.optDouble("mrp", 0.0)
                SizePreset(
                    o.optString("id").ifBlank { UUID.randomUUID().toString() },
                    o.optString("kind", NORMAL).ifBlank { NORMAL },
                    o.optString("label"),
                    sizes,
                    if (m.isNaN() || m < 0) 0.0 else m
                )
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun toJson(list: List<SizePreset>): String {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().put("id", p.id).put("kind", p.kind).put("label", p.label)
                .put("sizes", JSONArray(p.sizes)).put("mrp", p.mrp))
        }
        return JSONObject().put("presets", arr).toString()
    }

    /**
     * Reads what the owner typed: "5-9" = 5,6,7,8,9. "5, 6, 7", "5 6 7", "5/6/7" = those sizes in that order.
     * Returns null when the text is not sizes (a letter, or a range that goes backwards).
     */
    fun parseSizes(text: String): List<String>? {
        val out = LinkedHashSet<String>()
        val normal = text.trim().replace(Regex("\\s*-\\s*"), "-")
        for (t in normal.split(Regex("[,\\s/]+")).filter { it.isNotEmpty() }) {
            val range = Regex("^(\\d{1,3})-(\\d{1,3})$").find(t)
            if (range != null) {
                val a = range.groupValues[1].toInt()
                val b = range.groupValues[2].toInt()
                if (a > b || b - a > 60) return null
                for (n in a..b) out.add(n.toString())
            } else if (Regex("^\\d{1,3}$").matches(t)) {
                out.add(t.toInt().toString())
            } else {
                return null
            }
        }
        return if (out.isEmpty()) null else out.toList()
    }
}
