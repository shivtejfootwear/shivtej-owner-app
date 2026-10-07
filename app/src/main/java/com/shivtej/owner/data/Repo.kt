package com.shivtej.owner.data

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.time.OffsetDateTime
import java.util.Date
import java.util.Locale
import java.util.UUID

// ------------------------------------------------------------------ models
data class SizeStock(val size: String, val qty: Int, val barcode: String)
data class Variant(val colour: String, val images: List<String>, val sizes: List<SizeStock>)
data class Product(
    val id: String,
    val article: String,
    val name: String,
    val brand: String,
    val category: String,
    val description: String,
    val mrp: Double,
    val price: Double,
    val active: Boolean,
    val newItem: Boolean,
    val images: List<String>,
    val variants: List<Variant>
) {
    val totalStock: Int get() = variants.sumOf { v -> v.sizes.sumOf { it.qty } }
    val allImages: List<String> get() = images + variants.flatMap { it.images }
}

data class Hit(val product: Product, val colour: String, val size: SizeStock)

data class CartLine(
    val productId: String,
    val article: String,
    val name: String,
    val colour: String,
    val size: String,
    val barcode: String,
    val qty: Int,
    val price: Double,
    val maxQty: Int
) { val total: Double get() = price * qty }

data class BillItem(
    val productId: String, val article: String, val name: String, val colour: String,
    val size: String, val barcode: String, val qty: Int, val price: Double
)

val ORDER_STATUSES = listOf("New", "Confirmed", "Packed", "Shipped", "Delivered", "Cancelled")
/** Statuses the owner can pick, plus the two the server sets itself when a return / exchange is processed. */
val ALL_ORDER_STATUSES = ORDER_STATUSES + listOf("Returned", "Exchanged")

/** One line of a return / exchange (items coming back, or replacement items handed out). */
data class RetLine(
    val productId: String, val article: String, val name: String,
    val colour: String, val size: String, val qty: Int, val price: Double
)

fun retLineKey(l: RetLine): String = l.productId + "|" + l.colour.lowercase() + "|" + l.size

fun parseRetLines(json: String): List<RetLine> = try {
    val a = JSONArray(json)
    (0 until a.length()).mapNotNull { i ->
        val o = a.optJSONObject(i) ?: return@mapNotNull null
        RetLine(
            o.str("productId").ifBlank { o.str("id") }, o.str("article"), o.str("name"), o.str("colour"), o.str("size"),
            if (o.has("qty")) o.optInt("qty", 1) else o.optInt("q", 1), o.optDouble("price", 0.0)
        )
    }
} catch (e: Exception) { emptyList() }

// ------------------------------------------------------------------ json helpers
fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k, "")
fun JSONObject.jsonText(k: String, def: String): String = if (isNull(k)) def else opt(k).toString()

fun JSONArray.strings(): List<String> = (0 until length()).mapNotNull { i ->
    when (val v = opt(i)) {
        is String -> v
        is Number -> v.toString().removeSuffix(".0")
        is JSONObject -> v.str("url").ifBlank { null }
        else -> null
    }
}

fun List<String>.toJsonText(): String = JSONArray(this).toString()
fun String.toStrList(): List<String> = try { JSONArray(this).strings() } catch (e: Exception) { emptyList() }

fun parseBillItems(json: String): List<BillItem> = try {
    val a = JSONArray(json)
    (0 until a.length()).map { i ->
        val o = a.getJSONObject(i)
        BillItem(o.str("productId"), o.str("article"), o.str("name"), o.str("colour"), o.str("size"),
            o.str("barcode"), o.optInt("qty", 1), o.optDouble("price", 0.0))
    }
} catch (e: Exception) { emptyList() }

fun parseTime(s: String): Long = try { OffsetDateTime.parse(s).toInstant().toEpochMilli() } catch (e: Exception) { System.currentTimeMillis() }

private fun sizeKey(s: String): Double = s.toDoubleOrNull() ?: 9999.0

// ------------------------------------------------------------------ repository
class Repo(private val db: AppDb, val prefs: Prefs) {
    private val dao = db.dao()
    var onChanged: () -> Unit = {}

    val products: Flow<List<Product>> =
        combine(dao.observeProducts(), dao.observeVariants(), dao.observeSizes()) { ps, vs, ss ->
            val vmap = vs.groupBy { it.productId }
            val smap = ss.groupBy { it.productId }
            ps.map { p ->
                val sizes = smap[p.id] ?: emptyList()
                val variants = (vmap[p.id] ?: emptyList()).map { v ->
                    Variant(
                        v.colour, v.images.toStrList(),
                        sizes.filter { it.colour == v.colour }.sortedBy { sizeKey(it.size) }
                            .map { SizeStock(it.size, it.qty, it.barcode) }
                    )
                }.sortedBy { it.colour.lowercase() }
                Product(p.id, p.article, p.name, p.brand, p.category, p.description, p.mrp, p.price,
                    p.active, p.newItem, p.images.toStrList(), variants)
            }
        }

    val bills = dao.observeBills()
    val stockLog = dao.observeLog()
    val orders = dao.observeOrders()
    val customers = dao.observeCustomers()
    val returns = dao.observeReturns()
    val pendingOps = dao.observePending()
    val failedOps = dao.observeFailed()
    val failedOpList = dao.observeFailedOps()

    private suspend fun enqueue(type: String, ref: String, payload: JSONObject, opId: String = UUID.randomUUID().toString()) {
        dao.addOp(OpE(opId = opId, type = type, refId = ref, payload = payload.toString(), createdAt = System.currentTimeMillis()))
    }

    // ---------------------------------------------------------- products
    suspend fun load(id: String): Product? {
        val p = dao.product(id) ?: return null
        val sizes = dao.sizesOf(id)
        val vars = dao.variantsOf(id).map { v ->
            Variant(v.colour, v.images.toStrList(),
                sizes.filter { it.colour == v.colour }.sortedBy { sizeKey(it.size) }
                    .map { SizeStock(it.size, it.qty, it.barcode) })
        }.sortedBy { it.colour.lowercase() }
        return Product(p.id, p.article, p.name, p.brand, p.category, p.description, p.mrp, p.price,
            p.active, p.newItem, p.images.toStrList(), vars)
    }

    fun productJson(p: Product): JSONObject {
        val cols = JSONArray()
        for (v in p.variants) {
            val sizes = JSONArray()
            for (s in v.sizes) sizes.put(JSONObject().put("size", s.size).put("qty", s.qty).put("barcode", s.barcode))
            cols.put(JSONObject().put("name", v.colour).put("images", JSONArray(v.images)).put("sizes", sizes))
        }
        return JSONObject()
            .put("id", p.id).put("article", p.article).put("name", p.name)
            .put("description", p.description).put("brand", p.brand).put("category", p.category)
            .put("price", p.price).put("mrp", p.mrp).put("active", p.active).put("new_item", p.newItem)
            .put("images", JSONArray(p.images)).put("colours", cols)
    }

    suspend fun saveProduct(p: Product, isNew: Boolean) {
        require(p.name.isNotBlank()) { "Product name is required" }
        require(p.article.isNotBlank()) { "Article is required" }
        require(p.price > 0) { "Selling price must be more than 0" }
        require(p.variants.isNotEmpty()) { "Add at least one colour" }
        require(p.variants.map { it.colour.trim().lowercase() }.toSet().size == p.variants.size) { "Duplicate colour names" }
        val seen = HashSet<String>()
        for (v in p.variants) {
            require(v.colour.isNotBlank()) { "Colour name is required" }
            require(v.sizes.map { it.size.trim() }.toSet().size == v.sizes.size) { "Duplicate size in ${v.colour}" }
            for (s in v.sizes) {
                require(s.size.isNotBlank()) { "Size is required in ${v.colour}" }
                if (s.barcode.isBlank()) continue
                require(seen.add(s.barcode)) { "Barcode ${s.barcode} is used twice in this product" }
                val others = dao.sizesByBarcode(s.barcode).filter { it.productId != p.id }
                require(others.isEmpty()) { "Barcode ${s.barcode} already belongs to another product" }
            }
        }
        db.withTransaction {
            val old = dao.sizesOf(p.id).associateBy { it.colour + "|" + it.size }
            val now = System.currentTimeMillis()
            dao.putProduct(ProductE(p.id, p.article.trim(), p.name.trim(), p.brand.trim(), p.category.trim(),
                p.description.trim(), p.mrp, p.price, p.active, p.newItem, p.images.toJsonText(), now))
            dao.delVariants(p.id)
            dao.delSizes(p.id)
            dao.putVariants(p.variants.map { VariantE(p.id, it.colour, it.images.toJsonText()) })
            dao.putSizes(p.variants.flatMap { v ->
                v.sizes.map { SizeE(p.id, v.colour, it.size, it.qty.coerceAtLeast(0), it.barcode) }
            })
            enqueue(if (isNew) "ADD_PRODUCT" else "UPDATE_PRODUCT", p.id, productJson(p))
            for (v in p.variants) for (s in v.sizes) {
                val o = old[v.colour + "|" + s.size]
                if (o == null) {
                    if (s.qty > 0) dao.putLog(StockLogE(UUID.randomUUID().toString(), p.id, p.article, v.colour,
                        s.size, s.qty, s.qty, "INITIAL", now, true))
                } else if (o.qty != s.qty) {
                    val d = s.qty - o.qty
                    val opId = UUID.randomUUID().toString()
                    dao.putLog(StockLogE(opId, p.id, p.article, v.colour, s.size, d, s.qty, "EDIT", now, false))
                    enqueue("UPDATE_STOCK", p.id, stockPayload(p.id, v.colour, s.size, d, opId, "EDIT"), opId)
                }
            }
        }
        onChanged()
    }

    private fun stockPayload(pid: String, colour: String, size: String, delta: Int, opId: String, reason: String) =
        JSONObject().put("productId", pid).put("colour", colour).put("size", size)
            .put("delta", delta).put("opId", opId).put("reason", reason)

    suspend fun deleteProduct(id: String) {
        db.withTransaction {
            dao.delProduct(id); dao.delVariants(id); dao.delSizes(id)
            enqueue("DELETE_PRODUCT", id, JSONObject().put("id", id))
        }
        onChanged()
    }

    suspend fun newBarcode(taken: Set<String> = emptySet()): String {
        repeat(60) {
            val c = prefs.barcodePrefix + (10000000..99999999).random()
            if (c !in taken && dao.sizesByBarcode(c).isEmpty()) return c
        }
        return prefs.barcodePrefix + System.currentTimeMillis()
    }

    suspend fun lookup(code: String): Hit? {
        val s = dao.sizesByBarcode(code.trim()).firstOrNull() ?: return null
        val p = load(s.productId) ?: return null
        val size = p.variants.firstOrNull { it.colour == s.colour }?.sizes?.firstOrNull { it.size == s.size } ?: return null
        return Hit(p, s.colour, size)
    }

    suspend fun assignBarcode(pid: String, colour: String, size: String, code: String) {
        val c = code.trim()
        require(c.isNotEmpty()) { "Barcode is empty" }
        val clash = dao.sizesByBarcode(c).filter { !(it.productId == pid && it.colour == colour && it.size == size) }
        require(clash.isEmpty()) { "This barcode is already assigned to another item" }
        val cur = dao.sizesOf(pid).firstOrNull { it.colour == colour && it.size == size }
        requireNotNull(cur) { "Item not found" }
        db.withTransaction {
            dao.putSizes(listOf(cur.copy(barcode = c)))
            val p = load(pid)!!
            enqueue("BARCODE_ASSIGNMENT", pid, productJson(p))
        }
        onChanged()
    }

    // ---------------------------------------------------------- stock
    suspend fun adjustStock(pid: String, colour: String, size: String, delta: Int, reason: String): Int {
        var after = 0
        db.withTransaction {
            val before = dao.qtyOf(pid, colour, size) ?: throw IllegalArgumentException("Item not found")
            dao.adjust(pid, colour, size, delta)
            after = dao.qtyOf(pid, colour, size) ?: before
            val real = after - before
            val art = dao.product(pid)?.article ?: ""
            val opId = UUID.randomUUID().toString()
            dao.putLog(StockLogE(opId, pid, art, colour, size, real, after, reason, System.currentTimeMillis(), false))
            if (real != 0) enqueue("UPDATE_STOCK", pid, stockPayload(pid, colour, size, real, opId, reason), opId)
        }
        onChanged()
        return after
    }

    suspend fun setStock(pid: String, colour: String, size: String, target: Int): Int {
        val cur = dao.qtyOf(pid, colour, size) ?: throw IllegalArgumentException("Item not found")
        return adjustStock(pid, colour, size, target.coerceAtLeast(0) - cur, "SET_EXACT")
    }

    // ---------------------------------------------------------- POS
    private fun nextBillNo(): String {
        val n = prefs.billCounter + 1
        prefs.billCounter = n
        return "B" + SimpleDateFormat("yyMMdd", Locale.US).format(Date()) + "-" + n.toString().padStart(4, '0')
    }

    suspend fun createBill(lines: List<CartLine>, method: String): BillE {
        require(lines.isNotEmpty()) { "Bill is empty" }
        val id = UUID.randomUUID().toString()
        val no = nextBillNo()
        val now = System.currentTimeMillis()
        val total = lines.sumOf { it.total }
        val items = JSONArray()
        for (l in lines) items.put(JSONObject().put("productId", l.productId).put("article", l.article)
            .put("name", l.name).put("colour", l.colour).put("size", l.size).put("barcode", l.barcode)
            .put("qty", l.qty).put("price", l.price))
        val bill = BillE(id, no, total, method, items.toString(), now, false)
        db.withTransaction {
            dao.putBill(bill)
            lines.forEachIndexed { i, l ->
                val before = dao.qtyOf(l.productId, l.colour, l.size) ?: 0
                dao.adjust(l.productId, l.colour, l.size, -l.qty)
                val after = dao.qtyOf(l.productId, l.colour, l.size) ?: 0
                dao.putLog(StockLogE("$id#${i + 1}", l.productId, l.article, l.colour, l.size,
                    after - before, after, "POS_SALE", now, false))
            }
            enqueue("CREATE_POS_BILL", id, JSONObject().put("id", id).put("bill_no", no).put("total", total)
                .put("payment_method", method).put("items", items)
                .put("created_at", java.time.Instant.ofEpochMilli(now).toString()))
        }
        onChanged()
        return bill
    }

    suspend fun bill(id: String): BillE? = dao.bill(id)

    // ---------------------------------------------------------- orders
    suspend fun updateOrder(id: String, status: String, tracking: String, courier: String, shipping: Double) {
        val o = dao.order(id) ?: return
        db.withTransaction {
            dao.putOrder(o.copy(orderStatus = status, trackingNo = tracking, courier = courier,
                shipping = shipping, pendingSync = true))
            enqueue("UPDATE_ORDER", id, JSONObject().put("id", id).put("status", status)
                .put("tracking", tracking).put("courier", courier).put("shipping", shipping))
        }
        onChanged()
    }

    // ---------------------------------------------------------- returns / exchanges
    private fun retJson(l: RetLine) = JSONObject().put("productId", l.productId).put("article", l.article)
        .put("name", l.name).put("colour", l.colour).put("size", l.size).put("qty", l.qty).put("price", l.price)

    /**
     * Saves a return or exchange on this phone at once (works offline) and queues it for the server.
     * Stock moves with fixed log ids ("RET:<id>:in:<n>" / "RET:<id>:out:<n>") that are identical on the server,
     * so a retry or a later download can never count the same movement twice.
     */
    suspend fun processReturn(
        refType: String, refId: String, kind: String, reason: String, restock: Boolean, refund: Double,
        inLines: List<RetLine>, outLines: List<RetLine>
    ) {
        require(inLines.isNotEmpty()) { "Choose at least one item to return" }
        require(kind != "EXCHANGE" || outLines.isNotEmpty()) { "Choose the replacement item" }
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val inArr = JSONArray(); inLines.forEach { inArr.put(retJson(it)) }
        val outArr = JSONArray(); outLines.forEach { outArr.put(retJson(it)) }
        db.withTransaction {
            for (l in outLines) {
                val have = dao.qtyOf(l.productId, l.colour, l.size) ?: 0
                require(have >= l.qty) { "Only $have left of ${l.name} (${l.colour} / ${l.size})" }
            }
            if (restock) inLines.forEachIndexed { i, l ->
                val before = dao.qtyOf(l.productId, l.colour, l.size)
                if (before != null) {
                    dao.adjust(l.productId, l.colour, l.size, l.qty)
                    val after = dao.qtyOf(l.productId, l.colour, l.size) ?: before
                    dao.putLog(StockLogE("RET:$id:in:${i + 1}", l.productId, l.article, l.colour, l.size,
                        after - before, after, if (kind == "EXCHANGE") "EXCHANGE_IN" else "RETURN", now, false))
                }
            }
            outLines.forEachIndexed { i, l ->
                val before = dao.qtyOf(l.productId, l.colour, l.size) ?: 0
                dao.adjust(l.productId, l.colour, l.size, -l.qty)
                val after = dao.qtyOf(l.productId, l.colour, l.size) ?: 0
                dao.putLog(StockLogE("RET:$id:out:${i + 1}", l.productId, l.article, l.colour, l.size,
                    after - before, after, "EXCHANGE_OUT", now, false))
            }
            dao.putReturn(ReturnE(id, kind, refType, refId, reason, refund, restock, inArr.toString(), outArr.toString(), now, true))
            enqueue("RETURN_EXCHANGE", id, JSONObject().put("id", id).put("kind", kind).put("ref_type", refType)
                .put("ref_id", refId).put("reason", reason).put("refund", refund).put("restock", restock)
                .put("items_in", inArr).put("items_out", outArr)
                .put("created_at", java.time.Instant.ofEpochMilli(now).toString()))
        }
        onChanged()
    }

    suspend fun returnSynced(id: String) = dao.returnSynced(id)

    /** The server refused this return (for example it was already returned elsewhere): forget it on this phone. */
    suspend fun dropRejectedReturn(id: String) {
        db.withTransaction { dao.delReturn(id); dao.delLogsStartingWith("RET:$id:") }
    }

    // ---------------------------------------------------------- sync helpers
    suspend fun pendingOps() = dao.pendingOps()
    suspend fun openOps() = dao.openOps()
    suspend fun markOp(id: Long, status: String, err: String?) = dao.markOp(id, status, err)
    suspend fun retryFailed() { dao.retryFailed(); onChanged() }
    suspend fun billSynced(id: String) = dao.billSynced(id)
    suspend fun orderSyncedIfIdle(id: String) { if (dao.pendingCountFor(id, "UPDATE_ORDER") == 0) dao.orderSynced(id) }
    suspend fun purgeOldDone() = dao.purgeDone(System.currentTimeMillis() - 3L * 24 * 3600 * 1000)

    suspend fun replaceImageRefs(pid: String, map: Map<String, String>) {
        if (map.isEmpty()) return
        db.withTransaction {
            dao.product(pid)?.let { p ->
                dao.putProduct(p.copy(images = p.images.toStrList().map { map[it] ?: it }.toJsonText()))
            }
            for (v in dao.variantsOf(pid)) {
                dao.putVariants(listOf(v.copy(images = v.images.toStrList().map { map[it] ?: it }.toJsonText())))
            }
        }
    }

    private fun parseProduct(o: JSONObject): Triple<ProductE, List<VariantE>, List<SizeE>> {
        val id = o.str("id")
        val pe = ProductE(
            id, o.str("article"), o.str("name"), o.str("brand"), o.str("category"), o.str("description"),
            o.optDouble("mrp", 0.0), o.optDouble("price", 0.0),
            if (o.isNull("active")) true else o.optBoolean("active", true),
            if (o.isNull("new_item")) false else o.optBoolean("new_item", false),
            (o.optJSONArray("images") ?: JSONArray()).strings().toJsonText(),
            parseTime(o.str("updated_at"))
        )
        val topSizes = (o.optJSONArray("sizes") ?: JSONArray()).strings()
        val vs = ArrayList<VariantE>()
        val ss = ArrayList<SizeE>()
        val cols = o.optJSONArray("colours") ?: JSONArray()
        for (i in 0 until cols.length()) {
            val c = cols.opt(i)
            if (c is JSONObject) {
                val name = c.str("name").ifBlank { c.str("colour") }
                if (name.isBlank()) continue
                vs.add(VariantE(id, name, (c.optJSONArray("images") ?: JSONArray()).strings().toJsonText()))
                val sa = c.optJSONArray("sizes") ?: JSONArray()
                for (j in 0 until sa.length()) {
                    val s = sa.opt(j)
                    if (s is JSONObject) {
                        val sz = s.str("size")
                        if (sz.isNotBlank()) ss.add(SizeE(id, name, sz, s.optInt("qty", 0).coerceAtLeast(0), s.str("barcode")))
                    } else if (s != null && s != JSONObject.NULL) {
                        ss.add(SizeE(id, name, s.toString().removeSuffix(".0"), 0, ""))
                    }
                }
            } else if (c is String && c.isNotBlank()) {
                vs.add(VariantE(id, c, "[]"))
                topSizes.forEach { ss.add(SizeE(id, c, it, 0, "")) }
            }
        }
        return Triple(pe, vs.distinctBy { it.colour }, ss.distinctBy { it.colour + "|" + it.size })
    }

    /** Products whose stock a still-waiting bill / return has already changed on this phone. */
    private fun productsTouchedBy(op: OpE): List<String> {
        if (op.status != "PENDING") return emptyList()
        return try {
            val p = JSONObject(op.payload)
            val out = ArrayList<String>()
            for (key in listOf("items", "items_in", "items_out")) {
                val a = p.optJSONArray(key) ?: continue
                for (i in 0 until a.length()) {
                    val id = a.optJSONObject(i)?.str("productId") ?: ""
                    if (id.isNotBlank()) out.add(id)
                }
            }
            out
        } catch (e: Exception) { emptyList() }
    }

    suspend fun applyServerProducts(arr: JSONArray) {
        db.withTransaction {
            // read the open queue INSIDE the transaction so a change made while we were downloading is never overwritten
            val protectedIds = dao.openOps().flatMap { listOf(it.refId) + productsTouchedBy(it) }.toSet()
            val serverIds = HashSet<String>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.str("id")
                if (id.isBlank()) continue
                serverIds.add(id)
                if (id in protectedIds) continue
                val (pe, vs, ss) = parseProduct(o)
                dao.putProduct(pe); dao.delVariants(id); dao.delSizes(id)
                dao.putVariants(vs); dao.putSizes(ss)
            }
            for (p in dao.allProducts()) {
                if (p.id !in serverIds && p.id !in protectedIds) {
                    dao.delProduct(p.id); dao.delVariants(p.id); dao.delSizes(p.id)
                }
            }
        }
    }

    suspend fun applyServerOrders(arr: JSONArray) = db.withTransaction {
        val protectedIds = dao.pendingOrderIds().toSet()
        val list = ArrayList<OrderE>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.str("id")
            if (id.isBlank() || id in protectedIds) continue
            val st = ALL_ORDER_STATUSES.firstOrNull { it.equals(o.str("order_status"), true) } ?: "New"
            list.add(OrderE(id, o.jsonText("customer", "{}"), o.jsonText("items", "[]"),
                o.optDouble("subtotal", 0.0), o.optDouble("shipping", 0.0), o.optDouble("total", 0.0),
                o.str("payment_status"), st, parseTime(o.str("created_at")), o.str("tracking_no"), o.str("courier"), false))
        }
        dao.putOrders(list)
    }

    suspend fun applyServerCustomers(arr: JSONArray) {
        val list = (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { it.str("mobile").isNotBlank() }
            .map { CustomerE(it.str("mobile"), it.str("name"), it.str("email")) }
        dao.putCustomers(list)
    }

    suspend fun applyServerReturns(arr: JSONArray) {
        val list = ArrayList<ReturnE>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.str("id")
            if (id.isBlank()) continue
            list.add(ReturnE(id, o.str("kind"), o.str("ref_type"), o.str("ref_id"), o.str("reason"),
                o.optDouble("refund", 0.0), if (o.isNull("restock")) true else o.optBoolean("restock", true),
                o.jsonText("items_in", "[]"), o.jsonText("items_out", "[]"), parseTime(o.str("created_at")), false))
        }
        dao.putReturns(list)
    }

    suspend fun applyServerBills(arr: JSONArray) {
        val list = (0 until arr.length()).map { arr.getJSONObject(it) }.filter { it.str("id").isNotBlank() }.map {
            BillE(it.str("id"), it.str("bill_no"), it.optDouble("total", 0.0), it.str("payment_method"),
                it.jsonText("items", "[]"), parseTime(it.str("created_at")), true)
        }
        dao.putBills(list)
    }

    /** Returns the highest server log id seen. */
    suspend fun applyServerLog(arr: JSONArray): Long {
        var max = 0L
        val list = ArrayList<StockLogE>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            max = maxOf(max, o.optLong("id", 0))
            list.add(StockLogE(o.str("op_id"), o.str("product_id"), o.str("article"), o.str("colour"), o.str("size"),
                o.optInt("delta", 0), if (o.isNull("qty_after")) null else o.optInt("qty_after"),
                o.str("reason"), parseTime(o.str("created_at")), true))
        }
        dao.putLogs(list)
        return max
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        db.clearAllTables()
        prefs.resetSyncState()
    }
}
