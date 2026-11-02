package com.shivtej.owner.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * One brand a supplier gives.
 * margin: the margin % the supplier gives on it. It is only a note for the owner (supplier notebook);
 *         it is never used to calculate a rate on the purchase bill.
 * sets:   the size sets of this brand that this supplier supplies, as keys "productId|6,7,8,9,10".
 *         A set belongs to one supplier only: ticking it here takes it away from the other supplier of the same brand.
 * setsReady: true once the owner's first auto-selection for this brand has been done.
 */
data class SupplierBrand(
    val brand: String,
    val margin: Double = 0.0,
    val sets: List<String> = emptyList(),
    val setsReady: Boolean = false
)

fun parseSupplierBrands(json: String): List<SupplierBrand> = runCatching {
    val a = JSONArray(json)
    (0 until a.length()).map { i ->
        val o = a.getJSONObject(i)
        val sa = o.optJSONArray("sets") ?: JSONArray()
        SupplierBrand(
            brand = o.optString("brand").trim().uppercase(),
            margin = o.optDouble("margin", 0.0),
            sets = (0 until sa.length()).map { sa.optString(it) }.filter { it.isNotBlank() },
            setsReady = o.optBoolean("setsReady", false)
        )
    }.filter { it.brand.isNotEmpty() }
}.getOrDefault(emptyList())

fun supplierBrandsArray(list: List<SupplierBrand>): JSONArray {
    val a = JSONArray()
    list.forEach {
        a.put(JSONObject().put("brand", it.brand).put("margin", it.margin)
            .put("sets", JSONArray(it.sets)).put("setsReady", it.setsReady))
    }
    return a
}

fun supplierBrandsJson(list: List<SupplierBrand>): String = supplierBrandsArray(list).toString()

/** One size set of a product: the sizes of one size group that the product has. */
data class ProductSet(val key: String, val label: String, val sizes: List<String>)

/** Key of one set of one product, as stored on a supplier's brand. */
fun setKey(productId: String, sizes: List<String>): String = productId + "|" + sizes.joinToString(",")

/**
 * The size sets of a product. The sizes of the product are split by the saved size groups (in their order),
 * so 6-10 and 11-12 become two ovals. A size in no group becomes its own oval.
 * Only the label (e.g. 6×10) is shown on the purchase bill; no price is shown.
 */
fun productSets(productId: String, sizes: List<String>, groups: List<List<String>>): List<ProductSet> {
    val all = sizes.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    val used = HashSet<String>()
    val out = ArrayList<ProductSet>()
    fun add(part: List<String>) {
        val label = if (part.size == 1) part[0] else part.first() + "×" + part.last()
        out.add(ProductSet(setKey(productId, part), label, part))
    }
    groups.forEach { g ->
        val part = g.filter { it in all && it !in used }
        if (part.isNotEmpty()) { used.addAll(part); add(part) }
    }
    all.filter { it !in used }.forEach { add(listOf(it)) }
    return out
}

/** The brand entry of this supplier for a brand name (case does not matter), or null. */
fun brandEntry(list: List<SupplierBrand>, brand: String): SupplierBrand? =
    list.firstOrNull { it.brand.equals(brand.trim(), ignoreCase = true) }

/**
 * Moves one set to the supplier that ticks it. Pure function: returns the new brands list per supplier id
 * (only the suppliers that change). suppliers: every supplier as (id, brands).
 * on = true: the set is ticked for supplierId and removed from every other supplier of the brand.
 * on = false: the set is removed from supplierId and given to the next other supplier of the same brand, if any.
 */
fun moveSet(
    suppliers: List<Pair<String, List<SupplierBrand>>>,
    supplierId: String,
    brand: String,
    key: String,
    on: Boolean
): Map<String, List<SupplierBrand>> {
    val byId = suppliers.toMap().toMutableMap()
    val changed = LinkedHashSet<String>()
    fun edit(id: String, f: (SupplierBrand) -> SupplierBrand) {
        val list = byId[id] ?: return
        byId[id] = list.map { if (it.brand.equals(brand.trim(), ignoreCase = true)) f(it) else it }
        changed.add(id)
    }
    // take the set away from every supplier of this brand first
    suppliers.forEach { (id, list) ->
        if (brandEntry(list, brand)?.sets?.contains(key) == true && (on || id == supplierId)) {
            edit(id) { it.copy(sets = it.sets - key) }
        }
    }
    if (on) {
        edit(supplierId) { it.copy(sets = it.sets + key, setsReady = true) }
    } else {
        val other = suppliers.firstOrNull { (id, list) -> id != supplierId && brandEntry(list, brand) != null }?.first
        if (other != null) edit(other) { it.copy(sets = it.sets + key) }
    }
    return changed.associateWith { byId[it]!! }
}

/** Moves many sets in one go (one tap on an article ticks or unticks all its sets). Returns the changed suppliers. */
fun moveSets(
    suppliers: List<Pair<String, List<SupplierBrand>>>,
    supplierId: String,
    brand: String,
    keys: List<String>,
    on: Boolean
): Map<String, List<SupplierBrand>> {
    var cur = suppliers
    val changed = LinkedHashMap<String, List<SupplierBrand>>()
    keys.forEach { k ->
        val m = moveSet(cur, supplierId, brand, k, on)
        changed.putAll(m)
        cur = cur.map { (id, l) -> id to (m[id] ?: l) }
    }
    return changed
}

/**
 * First-time selection for one brand of this supplier: every set of the brand that no other supplier of the brand
 * ticks is ticked here. Returns the supplier's new brands list, or null when nothing needs to change.
 */
fun initialBrandSets(
    suppliers: List<Pair<String, List<SupplierBrand>>>,
    supplierId: String,
    brand: String,
    setKeys: List<String>
): List<SupplierBrand>? {
    if (setKeys.isEmpty()) return null
    val mine = suppliers.firstOrNull { it.first == supplierId }?.second ?: return null
    val entry = brandEntry(mine, brand) ?: return null
    if (entry.setsReady) return null
    val taken = suppliers.filter { it.first != supplierId }
        .mapNotNull { brandEntry(it.second, brand) }.flatMap { it.sets }.toSet()
    val free = setKeys.filter { it !in taken }
    return mine.map { if (it === entry) it.copy(sets = (it.sets + free).distinct(), setsReady = true) else it }
}
