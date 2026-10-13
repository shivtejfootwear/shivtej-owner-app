package com.shivtej.owner.data

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.time.OffsetDateTime
import java.util.Date
import java.util.Locale
import java.util.UUID
import com.shivtej.owner.util.BillMath
import com.shivtej.owner.util.Ledger
import com.shivtej.owner.util.MathLine

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
    val variants: List<Variant>,
    val videos: List<String> = emptyList()     // product videos (local file or https URL), added in update 4
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
    val price: Double,                 // SELLING RATE (the owner can change it while billing)
    val maxQty: Int,
    val mrp: Double = 0.0              // printed MRP (0 = unknown -> the rate is used)
) { val total: Double get() = price * qty }

data class BillItem(
    val productId: String, val article: String, val name: String, val colour: String,
    val size: String, val barcode: String, val qty: Int, val price: Double,
    val mrp: Double = 0.0
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
            o.str("barcode"), o.optInt("qty", 1), o.optDouble("price", 0.0), o.optDouble("mrp", 0.0))
    }
} catch (e: Exception) { emptyList() }

/** Extra details saved with a POS bill. */
data class BillExtra(
    val salesPersonId: String = "",
    val salesPerson: String = "",
    val customerName: String = "",
    val customerMobile: String = "",
    val customerAddress: String = "",
    val taxPercent: Double = 0.0,
    val amountPaid: Double? = null        // null = paid in full
)

/** One line of a supplier purchase bill. */
data class PurchaseLine(
    val productId: String, val article: String, val name: String,
    val colour: String, val size: String, val qty: Int, val cost: Double
)

fun parsePurchaseLines(json: String): List<PurchaseLine> = try {
    val a = JSONArray(json)
    (0 until a.length()).mapNotNull { i ->
        val o = a.optJSONObject(i) ?: return@mapNotNull null
        PurchaseLine(o.str("productId"), o.str("article"), o.str("name"), o.str("colour"), o.str("size"),
            o.optInt("qty", 0), o.optDouble("cost", 0.0))
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
                    p.active, p.newItem, p.images.toStrList(), variants, p.videos.toStrList())
            }
        }

    val bills = dao.observeBills()
    val deletedBills = dao.observeDeletedBills()
    val stockLog = dao.observeLog()
    val orders = dao.observeOrders()
    val customers = dao.observeCustomers()
    val returns = dao.observeReturns()
    val staff = dao.observeStaff()
    val posCustomers = dao.observePosCustomers()
    val suppliers = dao.observeSuppliers()
    val purchases = dao.observePurchases()
    val supplierPayments = dao.observeSupplierPayments()
    val customerPayments = dao.observeCustomerPayments()
    val tasks = dao.observeTasks()
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
            p.active, p.newItem, p.images.toStrList(), vars, p.videos.toStrList())
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
            .put("videos", JSONArray(p.videos))
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
            // a colour / size that still has stock can not be removed from the product (its stock would vanish without a log)
            val kept = p.variants.flatMap { v -> v.sizes.map { v.colour + "|" + it.size } }.toSet()
            for ((key, o) in old) require(key in kept || o.qty == 0) {
                "Can not remove ${o.colour} / ${o.size}: it still has ${o.qty} in stock. Save it with stock 0 first, then remove it."
            }
            val now = System.currentTimeMillis()
            dao.putProduct(ProductE(p.id, p.article.trim(), p.name.trim(), p.brand.trim(), p.category.trim(),
                p.description.trim(), p.mrp, p.price, p.active, p.newItem, p.images.toJsonText(), now, p.videos.toJsonText()))
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
            // the stock of this product is written to the stock history first, so it is never lost without a trace
            val now = System.currentTimeMillis()
            val art = dao.product(id)?.article ?: ""
            for (s in dao.sizesOf(id)) if (s.qty != 0) dao.putLog(StockLogE(UUID.randomUUID().toString(), id, art,
                s.colour, s.size, -s.qty, 0, "PRODUCT_DELETED", now, true))
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

    /** The number the NEXT bill will get (shown on the billing screen; the counter itself only moves when a bill is saved). */
    fun peekBillNo(): String =
        "B" + SimpleDateFormat("yyMMdd", Locale.US).format(Date()) + "-" + (prefs.billCounter + 1).toString().padStart(4, '0')

    /**
     * Saves a POS bill. The stock part is exactly what it always was (one atomic change per line, log id "<billId>#<line>",
     * same id on the server). New in update 2: customer, sales person, MRP / discount / tax / payment details and
     * the shop + design snapshot are saved with the bill. Discount = MRP - selling rate (never an item column).
     */
    suspend fun createBill(lines: List<CartLine>, method: String, extra: BillExtra = BillExtra()): BillE {
        require(lines.isNotEmpty()) { "Bill is empty" }
        val id = UUID.randomUUID().toString()
        val no = nextBillNo()
        val now = System.currentTimeMillis()
        val t = BillMath.compute(lines.map { MathLine(it.mrp, it.price, it.qty) }, extra.taxPercent)
        val total = t.grandTotal
        val paid = BillMath.round2((extra.amountPaid ?: total).coerceAtLeast(0.0))
        val balance = BillMath.balance(total, paid)
        val mobile = BillMath.normMobile(extra.customerMobile)
        val cName = extra.customerName.trim()
        val cAddr = extra.customerAddress.trim()
        val items = JSONArray()
        for (l in lines) items.put(JSONObject().put("productId", l.productId).put("article", l.article)
            .put("name", l.name).put("colour", l.colour).put("size", l.size).put("barcode", l.barcode)
            .put("qty", l.qty).put("price", l.price).put("mrp", BillMath.lineMrp(l.mrp, l.price)))
        val meta = BillMeta.snapshot(prefs)
        val bill = BillE(id, no, total, method, items.toString(), now, false,
            salesPersonId = extra.salesPersonId, salesPerson = extra.salesPerson.trim(),
            customerName = cName, customerMobile = if (mobile.length == 10) mobile else "", customerAddress = cAddr,
            totalMrp = t.totalMrp, totalDiscount = t.totalDiscount, subTotal = t.subTotal,
            taxPercent = t.taxPercent, taxAmount = t.taxAmount, amountPaid = paid, balance = balance, metaJson = meta)
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
                .put("created_at", java.time.Instant.ofEpochMilli(now).toString())
                .put("sales_person_id", extra.salesPersonId).put("sales_person", bill.salesPerson)
                .put("customer_name", cName).put("customer_mobile", bill.customerMobile).put("customer_address", cAddr)
                .put("total_mrp", t.totalMrp).put("total_discount", t.totalDiscount).put("sub_total", t.subTotal)
                .put("tax_percent", t.taxPercent).put("tax_amount", t.taxAmount)
                .put("amount_paid", paid).put("balance", balance).put("meta", JSONObject(meta)))
            // A customer who is already saved keeps the name and address on the phone (the bill only fills blanks).
            // Photo, opening balance and created date are copied, never rebuilt, so nothing is lost.
            // A NEW customer is not saved here: the receipt screen offers to save them (see SaveCustomerOffer).
            if (bill.customerMobile.isNotEmpty()) {
                val old = dao.posCustomer(bill.customerMobile)
                if (old != null) {
                    val name = old.name.ifBlank { cName }
                    val addr = old.address.ifBlank { cAddr }
                    if (name != old.name || addr != old.address) {
                        dao.putPosCustomer(old.copy(name = name, address = addr, pendingSync = true))
                        enqueue("UPSERT_CUSTOMER", bill.customerMobile, JSONObject().put("mobile", bill.customerMobile)
                            .put("name", name).put("address", addr).put("force", false)
                            .put("created_at", java.time.Instant.ofEpochMilli(old.createdAt).toString()))
                    }
                }
            }
        }
        if (extra.salesPersonId.isNotBlank()) prefs.lastSalesPersonId = extra.salesPersonId
        onChanged()
        return bill
    }

    suspend fun bill(id: String): BillE? = dao.bill(id)

    /**
     * Deletes a saved POS bill ("डिलीट") and reverses it everywhere:
     *  - every item goes back into stock: same product, colour and size. The barcode lives on that size row and is
     *    never removed by a sale, so it comes back with the item;
     *  - the customer payments made against this bill are reversed (they stop counting in ledgers and reports);
     *  - the bill is kept as deleted (not erased) and the server gets the same change (DELETE_POS_BILL).
     * Returns how many items could not go back to stock (the product was removed from the catalogue after the sale).
     */
    suspend fun deleteBill(billId: String): Int {
        var notRestored = 0
        db.withTransaction {
            val b = dao.bill(billId) ?: throw IllegalArgumentException("This bill was not found")
            require(!b.deleted) { "This bill is already deleted" }
            val now = System.currentTimeMillis()
            parseBillItems(b.itemsJson).forEachIndexed { i, l ->
                val before = dao.qtyOf(l.productId, l.colour, l.size)
                if (before == null) { notRestored++; return@forEachIndexed }
                dao.adjust(l.productId, l.colour, l.size, l.qty)
                val after = dao.qtyOf(l.productId, l.colour, l.size) ?: before
                dao.putLog(StockLogE("$billId#del${i + 1}:$now", l.productId, l.article, l.colour, l.size,
                    after - before, after, "BILL_DELETE", now, false))
            }
            dao.markBillDeleted(billId, now)
            dao.markPaymentsDeletedOfBill(billId)
            enqueue("DELETE_POS_BILL", billId, JSONObject().put("id", billId).put("deleted_at", iso(now))
                .put("cycle", now.toString()))
        }
        onChanged()
        return notRestored
    }

    /**
     * Restores a deleted bill ("रिस्टोर"): its items are taken out of stock again and its customer payments count again.
     * Nothing changes unless every line is still in stock (the product must exist and have enough pieces).
     */
    suspend fun restoreBill(billId: String) {
        db.withTransaction {
            val b = dao.bill(billId) ?: throw IllegalArgumentException("This bill was not found")
            require(b.deleted) { "This bill is not deleted" }
            val items = parseBillItems(b.itemsJson)
            // check every line first
            for (l in items) {
                val have = dao.qtyOf(l.productId, l.colour, l.size)
                    ?: throw IllegalArgumentException("${l.article} ${l.colour} ${l.size}: यह प्रोडक्ट या साइज़ अब मौजूद नहीं है")
                require(have >= l.qty) { "${l.article} ${l.colour} ${l.size}: स्टॉक सिर्फ़ $have बचा है, ${l.qty} चाहिए" }
            }
            val now = System.currentTimeMillis()
            items.forEachIndexed { i, l ->
                val before = dao.qtyOf(l.productId, l.colour, l.size) ?: 0
                dao.adjust(l.productId, l.colour, l.size, -l.qty)
                val after = dao.qtyOf(l.productId, l.colour, l.size) ?: before
                dao.putLog(StockLogE("$billId#res${i + 1}:$now", l.productId, l.article, l.colour, l.size,
                    after - before, after, "BILL_RESTORE", now, false))
            }
            dao.markBillRestored(billId)
            dao.markPaymentsRestoredOfBill(billId)
            enqueue("RESTORE_POS_BILL", billId, JSONObject().put("id", billId).put("cycle", now.toString()))
        }
        onChanged()
    }

    // ---------------------------------------------------------- staff (people who make sales)
    private fun iso(ms: Long): String = java.time.Instant.ofEpochMilli(ms).toString()

    /** Adds a staff member (id = null) or edits one. Nobody is ever deleted: inactive staff keep their sales history. */
    suspend fun saveStaff(id: String?, name: String, mobile: String, active: Boolean): StaffE {
        val n = name.trim()
        require(n.isNotEmpty()) { "Staff name is required" }
        val m = BillMath.normMobile(mobile)
        require(mobile.isBlank() || m.length == 10) { "Mobile number must be 10 digits" }
        var saved: StaffE? = null
        db.withTransaction {
            val all = dao.allStaff()
            require(all.none { it.id != id && it.name.trim().equals(n, true) }) { "A staff member named $n already exists" }
            val old = if (id == null) null else dao.staffById(id)
            val s = StaffE(old?.id ?: UUID.randomUUID().toString(), n, if (mobile.isBlank()) "" else m, active,
                old?.createdAt ?: System.currentTimeMillis(), true)
            dao.putStaff(s)
            enqueue("UPSERT_STAFF", s.id, JSONObject().put("id", s.id).put("name", s.name).put("mobile", s.mobile)
                .put("active", s.active).put("created_at", iso(s.createdAt)))
            saved = s
        }
        onChanged()
        return saved!!
    }

    // ---------------------------------------------------------- shop customers (one row per mobile number)
    /** Adds or edits a customer from the Customers screen. The values typed here replace the saved ones. */
    suspend fun saveCustomer(
        mobile: String, name: String, address: String, photoPath: String? = null, openingBalance: Double? = null
    ): PosCustomerE {
        val m = BillMath.normMobile(mobile)
        require(m.length == 10) { "Mobile number must be 10 digits" }
        require(name.trim().isNotEmpty()) { "Customer name is required" }
        require(openingBalance == null || (openingBalance.isFinite() && kotlin.math.abs(openingBalance) < 100_000_000.0)) {
            "Opening balance is not a valid amount"
        }
        var saved: PosCustomerE? = null
        db.withTransaction {
            val old = dao.posCustomer(m)
            val photo = photoPath ?: old?.photoPath ?: ""
            val c = PosCustomerE(m, name.trim(), address.trim(), old?.createdAt ?: System.currentTimeMillis(), true,
                photoPath = photo, photoUrl = if (photo.isBlank()) "" else old?.photoUrl ?: "",
                openingBalance = openingBalance?.let { BillMath.round2(it) } ?: old?.openingBalance ?: 0.0)
            dao.putPosCustomer(c)
            enqueue("UPSERT_CUSTOMER", m, JSONObject().put("mobile", m).put("name", c.name).put("address", c.address)
                .put("force", true).put("created_at", iso(c.createdAt)))
            // a removal must also reach the server when only the server copy still has a photo
            val removing = photoPath != null && photoPath.isBlank() && !old?.photoUrl.isNullOrBlank()
            if (photoPath != null && (photoPath != old?.photoPath || removing)) {
                enqueue("SET_CUSTOMER_PHOTO", m, JSONObject().put("mobile", m).put("photo_local", photoPath))
            }
            saved = c
        }
        onChanged()
        return saved!!
    }

    /** Brands already saved on products, A to Z, used as suggestions in the Brand field of Add Product. */
    suspend fun knownBrands(): List<String> =
        dao.allProducts().map { it.brand.trim() }.filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }.sortedBy { it.lowercase() }

    /** True when this mobile number is not a saved shop customer yet (the receipt then offers to save it). */
    suspend fun isNewShopCustomer(mobile: String): Boolean {
        val m = BillMath.normMobile(mobile)
        return m.length == 10 && dao.posCustomer(m) == null
    }

    // ---------------------------------------------------------- customer udhaar and payments (update 4)
    /**
     * Money received from a shop customer. [billId] empty = on account. It reduces the customer's total due.
     * Refused (nothing saved) when the customer owes nothing, or the amount is more than what is due / left on the bill.
     */
    suspend fun recordCustomerPayment(mobile: String, billId: String, amount: Double, method: String, note: String): CustomerPaymentE {
        val m = BillMath.normMobile(mobile)
        require(m.length == 10) { "Customer mobile number is needed" }
        val amt = BillMath.round2(amount)
        require(amt > 0.0) { "Payment amount must be more than 0" }
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val pay = CustomerPaymentE(id, m, billId, amt, method, note.trim(), now, true)
        db.withTransaction {
            val bills = dao.allBills()
            val payments = dao.allCustomerPayments()
            val orders = ledgerOrders(dao.allOrders())
            val returns = ledgerReturns(dao.allReturns())
            val sum = Ledger.summaryOf(m, bills, payments, orders, returns, openingsNow())
            require(sum.outstanding > 0.0) { "This customer has no balance due" }
            require(amt <= sum.outstanding + 0.005) { "Amount is more than the balance due (${sum.outstanding})" }
            if (billId.isNotBlank()) {
                val b = bills.firstOrNull { it.id == billId } ?: throw IllegalArgumentException("This bill was not found")
                require(BillMath.normMobile(b.customerMobile) == m) { "This bill belongs to another customer" }
                val left = Ledger.billRemaining(b, payments, returns)
                require(amt <= left + 0.005) { "Amount is more than what is left on this bill ($left)" }
            }
            dao.putCustomerPayment(pay)
            enqueue("ADD_CUSTOMER_PAYMENT", id, JSONObject().put("id", id).put("mobile", m).put("bill_id", billId)
                .put("amount", amt).put("method", method).put("note", pay.note).put("paid_at", iso(now)))
        }
        onChanged()
        return pay
    }

    suspend fun customerPaymentSynced(id: String) = dao.customerPaymentSynced(id)
    suspend fun customerPaymentRejected(id: String) = dao.customerPaymentRejectedCount(id) > 0

    /** The server refused this payment: it stays on this phone as REJECTED and is not counted anywhere. */
    suspend fun markRejectedCustomerPayment(opId: Long, id: String, why: String) {
        db.withTransaction {
            dao.markOp(opId, "REJECTED", why)
            dao.markCustomerPaymentRejected(id, why)
        }
    }

    suspend fun applyServerCustomerPayments(arr: JSONArray) = db.withTransaction {
        val protectedIds = (dao.pendingCustomerPaymentIds() + dao.rejectedCustomerPaymentIds()).toSet()
        // payments of a bill whose delete / restore is still on its way to the server keep this phone's state
        val billsInFlight = dao.billOpsInFlight().toSet()
        val list = ArrayList<CustomerPaymentE>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.str("id")
            if (id.isBlank() || id in protectedIds || o.str("bill_id") in billsInFlight) continue
            list.add(CustomerPaymentE(id, BillMath.normMobile(o.str("mobile")), o.str("bill_id"), o.optDouble("amount", 0.0),
                o.str("method"), o.str("note"), parseTime(o.str("paid_at")), false,
                deleted = o.optBoolean("deleted", false)))
        }
        dao.putCustomerPayments(list)
    }

    // ---------------------------------------------------------- tasks and reminders (update 4, kept on this phone)
    /** Creates or edits a task. dueAt = 0 means no due date. The caller schedules or cancels the Android reminder. */
    suspend fun saveTask(id: String?, title: String, details: String, dueAt: Long): TaskE {
        val t = title.trim()
        require(t.isNotEmpty()) { "Write what needs to be done" }
        val old = if (id == null) null else dao.task(id)
        val task = TaskE(
            old?.id ?: UUID.randomUUID().toString(), t, details.trim(), dueAt,
            old?.done ?: false, old?.doneAt ?: 0L, old?.createdAt ?: System.currentTimeMillis(),
            old?.notifyId ?: (java.util.Random().nextInt(Int.MAX_VALUE - 1) + 1)
        )
        dao.putTask(task)
        onChanged()
        return task
    }

    suspend fun setTaskDone(id: String, done: Boolean): TaskE? {
        val t = dao.task(id) ?: return null
        val nt = t.copy(done = done, doneAt = if (done) System.currentTimeMillis() else 0L)
        dao.putTask(nt)
        onChanged()
        return nt
    }

    suspend fun taskById(id: String): TaskE? = dao.task(id)

    suspend fun allTasks(): List<TaskE> = dao.allTasks()

    suspend fun deleteTask(id: String) {
        dao.deleteTask(id)
        onChanged()
    }

    // ---------------------------------------------------------- suppliers, purchase bills, payments
    suspend fun saveSupplier(id: String?, name: String, mobile: String, address: String, notes: String, active: Boolean, photoPath: String? = null): SupplierE {
        val n = name.trim()
        require(n.isNotEmpty()) { "Supplier name is required" }
        val m = BillMath.normMobile(mobile)
        require(mobile.isBlank() || m.length == 10) { "Mobile number must be 10 digits" }
        var saved: SupplierE? = null
        db.withTransaction {
            val all = dao.allSuppliers()
            require(all.none { it.id != id && it.name.trim().equals(n, true) }) { "A supplier named $n already exists" }
            val old = if (id == null) null else dao.supplier(id)
            val s = SupplierE(old?.id ?: UUID.randomUUID().toString(), n, if (mobile.isBlank()) "" else m,
                address.trim(), notes.trim(), active, old?.createdAt ?: System.currentTimeMillis(), true,
                photoPath = photoPath ?: old?.photoPath ?: "", photoUrl = if (photoPath == "") "" else old?.photoUrl ?: "")
            dao.putSupplier(s)
            enqueue("UPSERT_SUPPLIER", s.id, JSONObject().put("id", s.id).put("name", s.name).put("mobile", s.mobile)
                .put("address", s.address).put("notes", s.notes).put("active", s.active).put("created_at", iso(s.createdAt)))
            val removing = photoPath != null && photoPath.isBlank() && !old?.photoUrl.isNullOrBlank()
            if (photoPath != null && (photoPath != old?.photoPath || removing)) {   // after the supplier itself is saved on the server
                enqueue("SET_SUPPLIER_PHOTO", s.id, JSONObject().put("id", s.id).put("photo_local", photoPath))
            }
            saved = s
        }
        onChanged()
        return saved!!
    }

    /**
     * Saves a purchase bill from a supplier. When [addStock] is on, every item is added to the stock right away
     * (log id "PUR:<id>:<line>", the same id the server uses, so it can never be counted twice).
     * A bill with no items is just a money entry (amount only) and changes no stock.
     * [paidNow] > 0 also saves a payment against this bill.
     */
    suspend fun createPurchase(
        supplierId: String, billNo: String, billDate: Long, lines: List<PurchaseLine>, amountIfNoItems: Double,
        addStock: Boolean, note: String, paidNow: Double, method: String, billFile: String = ""
    ): PurchaseE {
        require(supplierId.isNotBlank()) { "Choose a supplier" }
        val clean = lines.filter { it.qty > 0 }
        val amount = BillMath.round2(if (clean.isNotEmpty()) clean.sumOf { it.cost * it.qty } else amountIfNoItems)
        require(amount > 0.0) { "Purchase amount must be more than 0" }
        require(paidNow >= 0.0) { "Paid amount can not be negative" }
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val stock = addStock && clean.isNotEmpty()
        val arr = JSONArray()
        for (l in clean) arr.put(JSONObject().put("productId", l.productId).put("article", l.article).put("name", l.name)
            .put("colour", l.colour).put("size", l.size).put("qty", l.qty).put("cost", l.cost))
        val p = PurchaseE(id, supplierId, billNo.trim(), billDate, amount, arr.toString(), stock, note.trim(), true,
            billFile = billFile)
        db.withTransaction {
            if (stock) for (l in clean) {
                require(dao.qtyOf(l.productId, l.colour, l.size) != null) { "${l.name} (${l.colour} / ${l.size}) is not in the stock list" }
            }
            dao.putPurchase(p)
            if (stock) clean.forEachIndexed { i, l ->
                val before = dao.qtyOf(l.productId, l.colour, l.size) ?: 0
                dao.adjust(l.productId, l.colour, l.size, l.qty)
                val after = dao.qtyOf(l.productId, l.colour, l.size) ?: before
                dao.putLog(StockLogE("PUR:$id:${i + 1}", l.productId, l.article, l.colour, l.size,
                    after - before, after, "PURCHASE", now, false))
            }
            enqueue("CREATE_PURCHASE", id, JSONObject().put("id", id).put("supplier_id", supplierId)
                .put("bill_no", p.billNo).put("bill_date", iso(billDate)).put("amount", amount)
                .put("note", p.note).put("add_stock", stock).put("items", arr)
                .put("bill_file", billFile))   // local file; SyncManager uploads it and sends bill_file_url instead
            if (paidNow > 0.0) {
                val pid = UUID.randomUUID().toString()
                dao.putSupplierPayment(SupplierPaymentE(pid, supplierId, id, BillMath.round2(paidNow), method, "", now, true))
                enqueue("ADD_SUPPLIER_PAYMENT", pid, JSONObject().put("id", pid).put("supplier_id", supplierId).put("bill_id", id)
                    .put("amount", BillMath.round2(paidNow)).put("method", method).put("note", "").put("paid_at", iso(now)))
            }
        }
        onChanged()
        return p
    }

    /** Money paid to a supplier. [billId] empty = a payment on account. It reduces what is still owed. */
    suspend fun addSupplierPayment(supplierId: String, billId: String, amount: Double, method: String, note: String): SupplierPaymentE {
        require(amount > 0.0) { "Payment amount must be more than 0" }
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val pay = SupplierPaymentE(id, supplierId, billId, BillMath.round2(amount), method, note.trim(), now, true)
        db.withTransaction {
            dao.putSupplierPayment(pay)
            enqueue("ADD_SUPPLIER_PAYMENT", id, JSONObject().put("id", id).put("supplier_id", supplierId).put("bill_id", billId)
                .put("amount", pay.amount).put("method", method).put("note", pay.note).put("paid_at", iso(now)))
        }
        onChanged()
        return pay
    }

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
    /** The most cash that can be given back for a return on this sale (used by the return screen and checked on save). */
    suspend fun maxCashRefund(refType: String, refId: String, backValue: Double, outValue: Double): Double =
        maxCashRefundIn(refType, refId, backValue, outValue)

    private suspend fun maxCashRefundIn(refType: String, refId: String, backValue: Double, outValue: Double): Double =
        Ledger.maxCashRefund(refType, refId, backValue, outValue, dao.allBills(), ledgerOrders(dao.allOrders()),
            dao.allCustomerPayments(), ledgerReturns(dao.allReturns()), openingsNow())

    /** The opening balances of all shop customers (only the non-zero ones), keyed by mobile. */
    private suspend fun openingsNow(): Map<String, Double> =
        dao.allPosCustomers().filter { it.openingBalance != 0.0 }.associate { BillMath.normMobile(it.mobile) to it.openingBalance }

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
            // Cash goes back only for money the customer really has extra with the shop; the rest reduces the udhaar.
            require(kind == "EXCHANGE" || refund >= 0.0) { "A refund cannot be negative" }
            val cap = maxCashRefundIn(refType, refId, inLines.sumOf { it.qty * it.price }, outLines.sumOf { it.qty * it.price })
            require(refund <= cap + 0.005) {
                "Cash refund can be at most ${BillMath.round2(cap)}. The rest of the value is adjusted in the customer's udhaar."
            }
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

    /**
     * The server refused this return: it stays on this phone with status REJECTED and its reason (history kept).
     * Its stock movements are taken back through logged reversals.
     */
    suspend fun markRejectedReturn(opId: Long, id: String, why: String) {
        db.withTransaction {
            dao.markOp(opId, "REJECTED", why)
            reverseStockLogs("RET:$id:", "RETURN_REJECTED")
            dao.markReturnRejected(id, why)
        }
    }

    /**
     * Takes back the stock movements that were logged under [prefix] (exactly what they moved, clamped at 0 like the
     * stock itself). Each reversal is written to the stock history with its own id. The original movements are kept
     * (they will never be sent, so they are marked as not waiting).
     */
    private suspend fun reverseStockLogs(prefix: String, reason: String) {
        if (dao.logsStartingWith("REV:$prefix").isNotEmpty()) return    // already taken back: never twice
        val now = System.currentTimeMillis()
        for (l in dao.logsStartingWith(prefix)) {
            val before = dao.qtyOf(l.productId, l.colour, l.size) ?: 0
            if (dao.qtyOf(l.productId, l.colour, l.size) != null) dao.adjust(l.productId, l.colour, l.size, -l.delta)
            val after = dao.qtyOf(l.productId, l.colour, l.size) ?: before
            val real = after - before
            if (real != 0) dao.putLog(StockLogE("REV:${l.opId}", l.productId, l.article, l.colour, l.size,
                real, after, reason, now, true))
        }
        dao.markLogsSynced(prefix)
    }

    // ---------------------------------------------------------- sync helpers
    suspend fun pendingOps() = dao.pendingOps()
    suspend fun openOps() = dao.openOps()
    suspend fun markOp(id: Long, status: String, err: String?) = dao.markOp(id, status, err)
    suspend fun retryFailed() { dao.retryFailed(); onChanged() }
    suspend fun billSynced(id: String) = dao.billSynced(id)
    suspend fun orderSyncedIfIdle(id: String) { if (dao.pendingCountFor(id, "UPDATE_ORDER") == 0) dao.orderSynced(id) }
    suspend fun paymentRejected(id: String) = dao.paymentRejectedCount(id) > 0
    suspend fun staffSyncedIfIdle(id: String) { if (dao.pendingCountFor(id, "UPSERT_STAFF") == 0) dao.staffSynced(id) }
    suspend fun posCustomerSyncedIfIdle(mobile: String) { if (dao.pendingCountFor(mobile, "UPSERT_CUSTOMER") == 0) dao.posCustomerSynced(mobile) }
    suspend fun supplierSyncedIfIdle(id: String) { if (dao.pendingCountFor(id, "UPSERT_SUPPLIER") == 0) dao.supplierSynced(id) }
    suspend fun purchaseSynced(id: String) = dao.purchaseSynced(id)
    suspend fun paymentSynced(id: String) = dao.paymentSynced(id)

    /**
     * The server refused this purchase bill: it, its payments and their queued operations stay on this phone with
     * status REJECTED (history kept). Its stock is taken back through logged reversals.
     */
    suspend fun markRejectedPurchase(opId: Long, id: String, why: String) {
        db.withTransaction {
            dao.markOp(opId, "REJECTED", why)
            if (dao.purchaseById(id) == null) return@withTransaction
            reverseStockLogs("PUR:$id:", "PURCHASE_REJECTED")
            dao.markPurchaseRejected(id, why)
            dao.markPaymentsRejectedOfBill(id, why)
            for (pay in dao.paymentsOfBill(id)) dao.failPaymentOp(pay.id)
        }
    }

    /** A supplier payment the server refused: kept on this phone as REJECTED (not counted in any total). */
    suspend fun markRejectedPayment(opId: Long, id: String, why: String) {
        db.withTransaction {
            dao.markOp(opId, "REJECTED", why)
            dao.markPaymentRejected(id, why)
        }
    }

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
            parseTime(o.str("updated_at")),
            (o.optJSONArray("videos") ?: JSONArray()).strings().toJsonText()
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

    /**
     * Downloads products and overwrites the local copy of the products the server sends back. A product that is
     * missing from the response is NEVER deleted here: an empty, partial (for example a row limit) or failed download
     * must not remove stock. Products are only removed on this phone by the owner's own Delete action.
     * An invalid row makes the whole transaction fail, so nothing is half-written.
     */
    suspend fun applyServerProducts(arr: JSONArray) {
        db.withTransaction {
            // read the open queue INSIDE the transaction so a change made while we were downloading is never overwritten
            val protectedIds = dao.openOps().flatMap { listOf(it.refId) + productsTouchedBy(it) }.toSet()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.str("id")
                if (id.isBlank() || id in protectedIds) continue
                val (pe, vs, ss) = parseProduct(o)
                // upserts only: a size or colour missing from the server is kept here, never deleted (its stock is not lost)
                dao.putProduct(pe)
                dao.putVariants(vs); dao.putSizes(ss)
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
        val kept = dao.rejectedReturnIds().toSet()      // refused on this phone: keep the local REJECTED record
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.str("id")
            if (id.isBlank() || id in kept) continue
            list.add(ReturnE(id, o.str("kind"), o.str("ref_type"), o.str("ref_id"), o.str("reason"),
                o.optDouble("refund", 0.0), if (o.isNull("restock")) true else o.optBoolean("restock", true),
                o.jsonText("items_in", "[]"), o.jsonText("items_out", "[]"), parseTime(o.str("created_at")), false))
        }
        dao.putReturns(list)
    }

    suspend fun applyServerBills(arr: JSONArray) {
        db.withTransaction {
            // A bill made on this phone keeps its customer / sales person / design even when the server row has no such
            // details (for example the update SQL was not installed when it was uploaded): server values win only when present.
            val local = dao.allBillsWithDeleted().associateBy { it.id }
            // a delete or restore that has not reached the server yet decides the state on this phone for now
            val inFlight = dao.billOpsInFlight().toSet()
            val list = ArrayList<BillE>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.str("id")
                if (id.isBlank()) continue
                val l = local[id]
                fun txt(k: String, cur: String?): String = if (o.str(k).isNotBlank()) o.str(k) else cur ?: ""
                fun num(k: String, cur: Double?, def: Double): Double = if (!o.isNull(k) && o.has(k)) o.optDouble(k, def) else cur ?: def
                val meta = if (!o.isNull("meta") && o.has("meta")) o.opt("meta").toString() else l?.metaJson ?: ""
                val deleted = if (id in inFlight && l != null) l.deleted else o.optBoolean("deleted", false)
                list.add(BillE(
                    id, o.str("bill_no"), o.optDouble("total", 0.0), o.str("payment_method"),
                    o.jsonText("items", "[]"), parseTime(o.str("created_at")), true,
                    salesPersonId = txt("sales_person_id", l?.salesPersonId), salesPerson = txt("sales_person", l?.salesPerson),
                    customerName = txt("customer_name", l?.customerName), customerMobile = txt("customer_mobile", l?.customerMobile),
                    customerAddress = txt("customer_address", l?.customerAddress),
                    totalMrp = num("total_mrp", l?.totalMrp, 0.0), totalDiscount = num("total_discount", l?.totalDiscount, 0.0),
                    subTotal = num("sub_total", l?.subTotal, 0.0), taxPercent = num("tax_percent", l?.taxPercent, 0.0),
                    taxAmount = num("tax_amount", l?.taxAmount, 0.0), amountPaid = num("amount_paid", l?.amountPaid, -1.0),
                    balance = num("balance", l?.balance, 0.0), metaJson = meta,
                    deleted = deleted,
                    deletedAt = if (!deleted) 0L else (l?.deletedAt?.takeIf { it > 0L } ?: parseTime(o.str("deleted_at")))
                ))
            }
            dao.putBills(list)
        }
    }

    suspend fun applyServerStaff(arr: JSONArray) = db.withTransaction {
        val protectedIds = dao.pendingStaffIds().toSet()
        val list = ArrayList<StaffE>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.str("id")
            if (id.isBlank() || id in protectedIds) continue
            list.add(StaffE(id, o.str("name"), o.str("mobile"), if (o.isNull("active")) true else o.optBoolean("active", true),
                parseTime(o.str("created_at")), false))
        }
        dao.putStaffList(list)
    }

    suspend fun applyServerPosCustomers(arr: JSONArray) = db.withTransaction {
        val protectedIds = dao.pendingPosCustomerIds().toSet()
        val list = ArrayList<PosCustomerE>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val m = o.str("mobile")
            if (m.isBlank() || m in protectedIds) continue
            val old = dao.posCustomer(m)
            list.add(PosCustomerE(m, o.str("name"), o.str("address"), parseTime(o.str("created_at")), false,
                photoPath = old?.photoPath ?: "", photoUrl = o.str("photo_url").ifBlank { old?.photoUrl ?: "" },
                openingBalance = old?.openingBalance ?: 0.0))
        }
        dao.putPosCustomers(list)
    }

    suspend fun applyServerSuppliers(arr: JSONArray) = db.withTransaction {
        val protectedIds = dao.pendingSupplierIds().toSet()
        val list = ArrayList<SupplierE>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.str("id")
            if (id.isBlank() || id in protectedIds) continue
            val old = dao.supplier(id)
            list.add(SupplierE(id, o.str("name"), o.str("mobile"), o.str("address"), o.str("notes"),
                if (o.isNull("active")) true else o.optBoolean("active", true), parseTime(o.str("created_at")), false,
                photoPath = old?.photoPath ?: "", photoUrl = o.str("photo_url").ifBlank { old?.photoUrl ?: "" }))
        }
        dao.putSuppliers(list)
    }

    suspend fun applyServerPurchases(arr: JSONArray) = db.withTransaction {
        val protectedIds = (dao.pendingPurchaseIds() + dao.rejectedPurchaseIds()).toSet()
        val list = ArrayList<PurchaseE>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.str("id")
            if (id.isBlank() || id in protectedIds) continue
            // the phone's own copy of the bill photo / PDF is kept; the server only adds the URL
            val localFile = dao.purchaseById(id)?.billFile ?: ""
            list.add(PurchaseE(id, o.str("supplier_id"), o.str("bill_no"), parseTime(o.str("bill_date")),
                o.optDouble("amount", 0.0), o.jsonText("items", "[]"),
                if (o.isNull("add_stock")) false else o.optBoolean("add_stock", false), o.str("note"), false,
                billFile = localFile, billFileUrl = o.str("bill_file_url")))
        }
        dao.putPurchases(list)
    }

    suspend fun applyServerSupplierPayments(arr: JSONArray) = db.withTransaction {
        val protectedIds = (dao.pendingPaymentIds() + dao.rejectedPaymentIds()).toSet()
        val list = ArrayList<SupplierPaymentE>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.str("id")
            if (id.isBlank() || id in protectedIds) continue
            list.add(SupplierPaymentE(id, o.str("supplier_id"), o.str("bill_id"), o.optDouble("amount", 0.0),
                o.str("method"), o.str("note"), parseTime(o.str("paid_at")), false))
        }
        dao.putSupplierPayments(list)
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

}
