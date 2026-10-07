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
    val authError: Boolean = false
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
        try {
            withContext(Dispatchers.IO) {
                push()
                pull()
            }
            val now = System.currentTimeMillis()
            prefs.lastSync = now
            state.update { it.copy(running = false, lastSync = now, authError = false, lastError = null) }
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
            try {
                execute(op, code)
                repo.markOp(op.id, "DONE", null)
                when (op.type) {
                    "CREATE_POS_BILL" -> repo.billSynced(op.refId)
                    "UPDATE_ORDER" -> repo.orderSyncedIfIdle(op.refId)
                    "RETURN_EXCHANGE" -> repo.returnSynced(op.refId)
                }
            } catch (e: ApiError) {
                if (e.isAuth || e.status >= 500 || e.code == "PGRST202") throw e
                repo.markOp(op.id, "FAILED", e.message)
                if (op.type == "RETURN_EXCHANGE") repo.dropRejectedReturn(op.refId)
            }
        }
        repo.purgeOldDone()
    }

    private suspend fun execute(op: OpE, code: String) {
        when (op.type) {
            "ADD_PRODUCT", "UPDATE_PRODUCT", "BARCODE_ASSIGNMENT" -> {
                val p = JSONObject(op.payload)
                uploadImagesIn(p, op.refId)
                Api.rpc("owner_upsert_product", a(code).put("p", p))
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
            "RETURN_EXCHANGE" -> Api.rpc("owner_process_return", a(code).put("p", JSONObject(op.payload)))
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

    private suspend fun uploadImagesIn(p: JSONObject, pid: String) {
        val mapping = HashMap<String, String>()
        fun fix(arr: JSONArray?): JSONArray {
            val out = JSONArray()
            if (arr == null) return out
            for (i in 0 until arr.length()) {
                val s = arr.optString(i)
                if (s.startsWith("/")) {
                    val url = upload(s, pid)
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

    private fun upload(path: String, pid: String): String? {
        prefs.uploadedUrl(path)?.let { return it }
        val f = File(path)
        if (!f.exists()) return null
        val url = Api.uploadImage("$pid/${UUID.randomUUID()}.jpg", f.readBytes(), "image/jpeg")
        prefs.setUploaded(path, url)
        return url
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
