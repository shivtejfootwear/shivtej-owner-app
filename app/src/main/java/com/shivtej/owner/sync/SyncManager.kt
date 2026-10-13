package com.shivtej.owner.sync

import android.content.Context
import com.shivtej.owner.data.OpE
import com.shivtej.owner.data.Prefs
import com.shivtej.owner.data.Repo
import com.shivtej.owner.net.Api
import com.shivtej.owner.net.ApiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

data class SyncState(
    val running: Boolean = false,
    val lastSync: Long = 0,
    val lastError: String? = null,
    val authError: Boolean = false,
    /** Not an error: staff / customers / suppliers stay on this phone until "owner_update_2_migration.sql" is run. */
    val notice: String? = null
)

/**
 * Offline-first sync:
 *  1. push every queued operation in order (idempotent on the server through op ids / bill ids)
 *  2. pull products, orders, customers, bills and stock log
 * Stock is always sent as a DELTA, never as an absolute overwrite, so website/POS/owner changes never clobber each other.
 */
class SyncManager(
    private val repo: Repo,
    private val prefs: Prefs,
    scope: CoroutineScope,
    private val ctx: Context
) {
    val state = MutableStateFlow(SyncState(lastSync = prefs.lastSync))
    private val mutex = Mutex()
    private val kicks = Channel<Unit>(Channel.CONFLATED)

    /** Set while a sync run finds that the update-2 SQL is not installed on the server yet. */
    private var update2Missing = false
    /** Set while a sync run finds that the update-4 SQL (customer payments, photos, product videos) is not installed yet. */
    private var update4Missing = false
    /** Set while a sync run finds that the update-6 SQL (bill deletion) is not installed yet. */
    private var update6Missing = false

    private val UPDATE2_OPS = setOf("UPSERT_STAFF", "UPSERT_CUSTOMER", "UPSERT_SUPPLIER", "CREATE_PURCHASE", "ADD_SUPPLIER_PAYMENT")
    private val UPDATE4_OPS = setOf("ADD_CUSTOMER_PAYMENT", "SET_CUSTOMER_PHOTO", "SET_SUPPLIER_PHOTO")
    private val UPDATE6_OPS = setOf("DELETE_POS_BILL", "RESTORE_POS_BILL")

    init {
        scope.launch {
            for (k in kicks) {
                syncNow()
                delay(400)
            }
        }
    }

    fun kick() {
        kicks.trySend(Unit)
        if (prefs.ownerCode.isNotBlank()) SyncWorker.enqueueNow(ctx)
    }

    suspend fun syncNow(): Boolean = mutex.withLock {
        if (prefs.ownerCode.isBlank()) return@withLock false
        state.update { it.copy(running = true, lastError = null) }
        update2Missing = false
        update4Missing = false
        update6Missing = false
        try {
            withContext(Dispatchers.IO) {
                push()
                pull()
            }
            val now = System.currentTimeMillis()
            prefs.lastSync = now
            val note = when {
                update2Missing -> "Staff, customers, suppliers and purchases are saved on this phone. Run owner_update_2_migration.sql in Supabase to share them with the server."
                update4Missing -> "Customer payments, photos and product videos are saved on this phone. Run owner_update_4_media_ledger_tasks.sql in Supabase to share them with the server."
                update6Missing -> "Bill deletions are saved on this phone. Run owner_update_6_bill_delete.sql and owner_update_6b_bill_restore.sql in Supabase to share them with the server."
                else -> null
            }
            state.update { it.copy(running = false, lastSync = now, authError = false, lastError = null, notice = note) }
            true
        } catch (e: CancellationException) {
            state.update { it.copy(running = false) }
            throw e
        } catch (e: ApiError) {
            val msg = when {
                e.isAuth -> "Owner code was rejected by the server"
                e.code == "PGRST202" -> "Supabase setup SQL is not installed yet"
                else -> e.message
            }
            state.update { it.copy(running = false, authError = e.isAuth, lastError = msg) }
            false
        } catch (e: IOException) {
            state.update { it.copy(running = false, lastError = "No connection - changes are saved and will sync automatically") }
            false
        } catch (e: Exception) {
            state.update { it.copy(running = false, lastError = e.message ?: e.toString()) }
            false
        }
    }

    private fun a(code: String) = JSONObject().put("p_code", code)

    // ------------------------------------------------------------------ push
    private suspend fun push() {
        val code = prefs.ownerCode
        for (op in repo.pendingOps()) {
            // the purchase this payment belongs to was refused (kept as REJECTED on this phone): never send it
            if (op.type == "ADD_SUPPLIER_PAYMENT" && repo.paymentRejected(op.refId)) continue
            try {
                execute(op, code)
                repo.markOp(op.id, "DONE", null)
                when (op.type) {
                    "CREATE_POS_BILL" -> repo.billSynced(op.refId)
                    "UPDATE_ORDER" -> repo.orderSyncedIfIdle(op.refId)
                    "RETURN_EXCHANGE" -> repo.returnSynced(op.refId)
                    "UPSERT_STAFF" -> repo.staffSyncedIfIdle(op.refId)
                    "UPSERT_CUSTOMER" -> repo.posCustomerSyncedIfIdle(op.refId)
                    "UPSERT_SUPPLIER" -> repo.supplierSyncedIfIdle(op.refId)
                    "CREATE_PURCHASE" -> repo.purchaseSynced(op.refId)
                    "ADD_SUPPLIER_PAYMENT" -> repo.paymentSynced(op.refId)
                    "ADD_CUSTOMER_PAYMENT" -> repo.customerPaymentSynced(op.refId)
                }
            } catch (e: ApiError) {
                if (e.code == "PGRST202" && op.type in UPDATE2_OPS) {
                    // the update-2 SQL is not installed yet: keep the change waiting (nothing is lost) and carry on with the rest
                    update2Missing = true
                    continue
                }
                if (e.code == "PGRST202" && op.type in UPDATE4_OPS) {
                    // the update-4 SQL is not installed yet: same as above
                    update4Missing = true
                    continue
                }
                if (e.code == "PGRST202" && op.type in UPDATE6_OPS) {
                    // the update-6 SQL (bill deletion) is not installed yet: same as above
                    update6Missing = true
                    continue
                }
                if (e.isAuth || e.status >= 500 || e.code == "PGRST202") throw e
                // a clear refusal (400 / 409 / 422) keeps the record on this phone as REJECTED with the reason; timeouts and
                // rate limits stay FAILED and are retried
                val refused = e.status == 400 || e.status == 409 || e.status == 422
                val why = e.message ?: "Refused by the server"
                when {
                    refused && op.type == "RETURN_EXCHANGE" -> repo.markRejectedReturn(op.id, op.refId, why)
                    refused && op.type == "CREATE_PURCHASE" -> repo.markRejectedPurchase(op.id, op.refId, why)
                    refused && op.type == "ADD_SUPPLIER_PAYMENT" -> repo.markRejectedPayment(op.id, op.refId, why)
                    refused && op.type == "ADD_CUSTOMER_PAYMENT" -> repo.markRejectedCustomerPayment(op.id, op.refId, why)
                    else -> repo.markOp(op.id, "FAILED", e.message)
                }
            }
        }
        // sent operations are kept (no automatic deletion of sync history)
    }

    private suspend fun execute(op: OpE, code: String) {
        when (op.type) {
            "ADD_PRODUCT", "UPDATE_PRODUCT", "BARCODE_ASSIGNMENT" -> {
                val p = JSONObject(op.payload)
                uploadImagesIn(p, op.refId, code)
                Api.rpc("owner_upsert_product", a(code).put("p", p))
                syncVideos(code, op.refId, p)
            }
            "DELETE_PRODUCT" -> Api.rpc("owner_delete_product", a(code).put("p_id", op.refId))
            "UPDATE_STOCK" -> {
                val p = JSONObject(op.payload)
                Api.rpc("owner_apply_stock", a(code)
                    .put("p_product", p.getString("productId")).put("p_colour", p.getString("colour"))
                    .put("p_size", p.getString("size")).put("p_delta", p.getInt("delta"))
                    .put("p_op", p.getString("opId")).put("p_reason", p.optString("reason", "MANUAL")))
            }
            "CREATE_POS_BILL" -> Api.rpc("owner_create_bill", a(code).put("p", JSONObject(op.payload)))
            "ADD_CUSTOMER_PAYMENT" -> Api.rpc("owner_add_customer_payment", a(code).put("p", JSONObject(op.payload)))
            "DELETE_POS_BILL" -> Api.rpc("owner_delete_bill", a(code).put("p", JSONObject(op.payload)))
            "RESTORE_POS_BILL" -> Api.rpc("owner_restore_bill", a(code).put("p", JSONObject(op.payload)))
            "SET_CUSTOMER_PHOTO" -> {
                val p = JSONObject(op.payload)
                val url = photoUrlFor(p.optString("photo_local", ""), "customers/${p.getString("mobile")}") ?: return
                Api.rpc("owner_set_customer_photo", a(code).put("p_mobile", p.getString("mobile")).put("p_url", url))
            }
            "SET_SUPPLIER_PHOTO" -> {
                val p = JSONObject(op.payload)
                val url = photoUrlFor(p.optString("photo_local", ""), "suppliers/${p.getString("id")}") ?: return
                Api.rpc("owner_set_supplier_photo", a(code).put("p_id", p.getString("id")).put("p_url", url))
            }
            "RETURN_EXCHANGE" -> Api.rpc("owner_process_return", a(code).put("p", JSONObject(op.payload)))
            "UPSERT_STAFF" -> Api.rpc("owner_upsert_staff", a(code).put("p", JSONObject(op.payload)))
            "UPSERT_CUSTOMER" -> Api.rpc("owner_upsert_customer", a(code).put("p", JSONObject(op.payload)))
            "UPSERT_SUPPLIER" -> Api.rpc("owner_upsert_supplier", a(code).put("p", JSONObject(op.payload)))
            "CREATE_PURCHASE" -> {
                val p = JSONObject(op.payload)
                val local = p.optString("bill_file", "")
                p.remove("bill_file")
                if (local.isNotBlank()) {
                    val f = File(local)
                    // a missing local copy is skipped; a network failure throws IOException and the operation is retried
                    if (f.exists()) {
                        val isPdf = f.extension.equals("pdf", ignoreCase = true)
                        val ext = if (isPdf) "pdf" else "jpg"
                        val mime = if (isPdf) "application/pdf" else "image/jpeg"
                        val url = try {
                            Api.uploadFile("supplier-bills", "${op.refId}/${UUID.randomUUID()}.$ext", f.readBytes(), mime)
                        } catch (e: ApiError) {
                            // an upload problem (for example the bucket is not created yet) must NOT reject the bill: it is retried
                            throw IOException("The bill photo could not be uploaded (${e.message}). It will be retried.")
                        }
                        p.put("bill_file_url", url)
                    }
                }
                Api.rpc("owner_create_purchase", a(code).put("p", p))
            }
            "ADD_SUPPLIER_PAYMENT" -> Api.rpc("owner_add_supplier_payment", a(code).put("p", JSONObject(op.payload)))
            "UPDATE_ORDER" -> {
                val p = JSONObject(op.payload)
                Api.rpc("owner_update_order", a(code)
                    .put("p_id", p.getString("id")).put("p_status", p.getString("status"))
                    .put("p_tracking", p.optString("tracking", "")).put("p_courier", p.optString("courier", ""))
                    .put("p_shipping", p.optDouble("shipping", 0.0)))
            }
            else -> throw ApiError(400, null, "Unknown operation ${op.type}")
        }
    }

    /**
     * Uploads a profile photo and returns its URL. An empty local path means "remove the photo" (returns "").
     * Returns null when there is nothing to send (the local file is gone).
     */
    private fun photoUrlFor(local: String, folder: String): String? {
        if (local.isBlank()) return ""
        val f = File(local)
        if (!f.exists()) return null
        return Api.uploadFile("profile-photos", "$folder/${UUID.randomUUID()}.jpg", f.readBytes(), "image/jpeg")
    }

    /** Uploads the product videos that are still on this phone, then saves the full list on the server. */
    private suspend fun syncVideos(code: String, pid: String, p: JSONObject) {
        val arr = p.optJSONArray("videos") ?: return
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val s = arr.optString(i)
            when {
                s.startsWith("/") -> {
                    val f = File(s)
                    if (!f.exists()) continue
                    val url = prefs.uploadedUrl(s) ?: Api.uploadFile("product-videos", "$pid/${UUID.randomUUID()}.mp4",
                        f.readBytes(), videoMime(f)).also { prefs.setUploaded(s, it) }
                    out.put(url)
                }
                s.isNotBlank() -> out.put(s)
            }
        }
        try {
            Api.rpc("owner_set_product_videos", a(code).put("p_id", pid).put("p_videos", out))
        } catch (e: ApiError) {
            if (e.code == "PGRST202") update4Missing = true else throw e
        }
    }

    private fun videoMime(f: File): String = when (f.extension.lowercase()) {
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        else -> "video/mp4"
    }

    private suspend fun uploadImagesIn(p: JSONObject, pid: String, code: String) {
        val mapping = HashMap<String, String>()
        fun fix(arr: JSONArray?): JSONArray {
            val out = JSONArray()
            if (arr == null) return out
            for (i in 0 until arr.length()) {
                val s = arr.optString(i)
                if (s.startsWith("/")) {
                    val url = upload(s, pid, code)
                    if (url != null) { mapping[s] = url; out.put(url) }
                } else if (s.isNotBlank()) out.put(s)
            }
            return out
        }
        p.put("images", fix(p.optJSONArray("images")))
        val cols = p.optJSONArray("colours")
        if (cols != null) for (i in 0 until cols.length()) {
            val c = cols.getJSONObject(i)
            c.put("images", fix(c.optJSONArray("images")))
        }
        repo.replaceImageRefs(pid, mapping)
    }

    /** Sends one product photo to the private B2 bucket. Returns its object name, saved in products.images. */
    private fun upload(path: String, pid: String, code: String): String? {
        prefs.uploadedUrl(path)?.let { return it }
        val f = File(path)
        if (!f.exists()) return null
        val name = Api.uploadProductPhoto(code, pid, f.readBytes(), "image/jpeg")
        prefs.setUploaded(path, name)
        return name
    }

    // ------------------------------------------------------------------ pull
    private suspend fun pull() {
        val code = prefs.ownerCode
        (Api.rpc("owner_list_products", a(code)) as? JSONArray)?.let { repo.applyServerProducts(it) }
        (Api.rpc("owner_list_orders", a(code)) as? JSONArray)?.let { repo.applyServerOrders(it) }
        (Api.rpc("owner_list_customers", a(code)) as? JSONArray)?.let { repo.applyServerCustomers(it) }
        (Api.rpc("owner_list_bills", a(code)) as? JSONArray)?.let { repo.applyServerBills(it) }
        (Api.rpc("owner_list_returns", a(code)) as? JSONArray)?.let { repo.applyServerReturns(it) }
        (Api.rpc("owner_stock_log", a(code).put("p_after", prefs.lastLogId)) as? JSONArray)?.let {
            val max = repo.applyServerLog(it)
            if (max > prefs.lastLogId) prefs.lastLogId = max
        }
        // update 2 lists: when the extra SQL is not installed yet they are simply skipped (a normal state, not an error)
        pullUpdate2("owner_list_staff") { repo.applyServerStaff(it) }
        pullUpdate2("owner_list_pos_customers") { repo.applyServerPosCustomers(it) }
        pullUpdate2("owner_list_suppliers") { repo.applyServerSuppliers(it) }
        pullUpdate2("owner_list_purchases") { repo.applyServerPurchases(it) }
        pullUpdate2("owner_list_supplier_payments") { repo.applyServerSupplierPayments(it) }
        pullUpdate4("owner_list_customer_payments") { repo.applyServerCustomerPayments(it) }
    }

    private suspend fun pullUpdate2(fn: String, apply: suspend (JSONArray) -> Unit) {
        try {
            (Api.rpc(fn, a(prefs.ownerCode)) as? JSONArray)?.let { apply(it) }
        } catch (e: ApiError) {
            if (e.code == "PGRST202") update2Missing = true else throw e
        }
    }

    private suspend fun pullUpdate4(fn: String, apply: suspend (JSONArray) -> Unit) {
        try {
            (Api.rpc(fn, a(prefs.ownerCode)) as? JSONArray)?.let { apply(it) }
        } catch (e: ApiError) {
            if (e.code == "PGRST202") update4Missing = true else throw e
        }
    }

    /** Validates the owner code against the server. */
    suspend fun verifyCode(code: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Api.rpc("owner_ping", a(code))
            Result.success(Unit)
        } catch (e: ApiError) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
