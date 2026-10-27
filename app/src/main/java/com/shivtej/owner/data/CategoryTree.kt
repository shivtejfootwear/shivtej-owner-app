package com.shivtej.owner.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Product type with its categories, for example: चप्पल -> V BELT, PACK, TRIPLE BELT, CROSS BELT, X BELT.
 * Kept on this phone and on the server (table owner_category_tree). Every product keeps its type (productType)
 * and its category (subCategory). All English names are saved in CAPITAL letters.
 */
data class CatType(val name: String, val cats: List<String>)

object CategoryTree {
    /** Used only until the owner changes the tree for the first time. */
    fun defaults(): List<CatType> = listOf(
        CatType("चप्पल", listOf("V BELT", "PACK", "TRIPLE BELT", "CROSS BELT", "X BELT")),
        CatType("SPORTS SHOES", listOf("LACE", "WITHOUT LACE")),
        CatType("FORMAL SHOES", listOf("LACE", "WITHOUT LACE"))
    )

    fun parse(json: String): List<CatType> = try {
        val a = JSONObject(json).optJSONArray("types") ?: JSONArray()
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name").trim()
            if (name.isEmpty()) return@mapNotNull null
            val cs = o.optJSONArray("cats") ?: JSONArray()
            CatType(name, (0 until cs.length()).map { cs.optString(it).trim() }.filter { it.isNotEmpty() })
        }
    } catch (e: Exception) { emptyList() }

    fun toJson(list: List<CatType>): String {
        val arr = JSONArray()
        list.forEach { t -> arr.put(JSONObject().put("name", t.name).put("cats", JSONArray(t.cats))) }
        return JSONObject().put("types", arr).toString()
    }
}

/** Names are saved in capitals (Hindi letters stay as they are). */
fun capName(s: String): String = s.trim().uppercase()
/** First letter of every English word in CAPITAL, the rest small ("rahul kumar" -> "Rahul Kumar"). Hindi is not changed. */
fun titleCase(s: String): String =
    Regex("(^|\\s)(\\p{Ll})").replace(s.trim().lowercase()) { it.groupValues[1] + it.groupValues[2].uppercase() }
