package com.shivtej.staff

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** The owner closed the access (or another phone logged in with a new code). */
class NotAllowed : Exception("NOT_ALLOWED")
class ApiFail(msg: String) : Exception(msg)

/** Calls the staff functions in Supabase (staff_login, staff_check, staff_catalog, staff_location). */
object Api {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val base get() = BuildConfig.SUPABASE_URL
    private val key get() = BuildConfig.SUPABASE_KEY

    private fun rpc(fn: String, args: JSONObject): Any? {
        val req = Request.Builder()
            .url("$base/rest/v1/rpc/$fn")
            .header("apikey", key)
            .header("Content-Type", "application/json")
            .post(args.toString().toRequestBody(JSON))
            .build()
        client.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                if (text.contains("NOT_ALLOWED")) throw NotAllowed()
                val msg = runCatching { JSONObject(text).optString("message") }.getOrNull().orEmpty()
                throw ApiFail(msg.ifBlank { "HTTP ${r.code}" })
            }
            return if (text.isBlank()) null else JSONTokener(text).nextValue()
        }
    }

    /** Returns {token, name, hours} or {error: WRONG_CODE | NO_CODE}. */
    fun login(mobile: String, code: String, device: String, deviceName: String): JSONObject =
        rpc("staff_login", JSONObject().put("p_mobile", mobile).put("p_code", code)
            .put("p_device", device).put("p_device_name", deviceName)) as JSONObject

    fun check(token: String, device: String): JSONObject =
        rpc("staff_check", JSONObject().put("p_token", token).put("p_device", device)) as JSONObject

    fun catalog(token: String, device: String): JSONArray =
        (rpc("staff_catalog", JSONObject().put("p_token", token).put("p_device", device)) as? JSONArray) ?: JSONArray()

    // ---- Make Bill (pending bills the manager sends to the owner) ----
    fun lookup(token: String, device: String, code: String): JSONObject =
        rpc("staff_lookup", JSONObject().put("p_token", token).put("p_device", device).put("p_code", code)) as JSONObject

    fun billSend(token: String, device: String, items: JSONArray): JSONObject =
        rpc("staff_bill_send", JSONObject().put("p_token", token).put("p_device", device).put("p_items", items)) as JSONObject

    fun billUpdate(token: String, device: String, id: String, items: JSONArray): JSONObject =
        rpc("staff_bill_update", JSONObject().put("p_token", token).put("p_device", device).put("p_id", id).put("p_items", items)) as JSONObject

    fun billDelete(token: String, device: String, id: String): JSONObject =
        rpc("staff_bill_delete", JSONObject().put("p_token", token).put("p_device", device).put("p_id", id)) as JSONObject

    fun billsList(token: String, device: String): JSONArray =
        (rpc("staff_bills_list", JSONObject().put("p_token", token).put("p_device", device)) as? JSONArray) ?: JSONArray()

    fun location(token: String, device: String, lat: Double?, lng: Double?, acc: Float?, battery: Int, status: String): JSONObject =
        rpc("staff_location", JSONObject()
            .put("p_token", token).put("p_device", device)
            .put("p_lat", lat ?: JSONObject.NULL).put("p_lng", lng ?: JSONObject.NULL)
            .put("p_acc", acc?.toDouble() ?: JSONObject.NULL)
            .put("p_battery", battery).put("p_status", status)) as JSONObject

    // Product photos kept in the private B2 bucket ("products/<id>/<uuid>.jpg") need a signed link (1 hour).
    private val signed = ConcurrentHashMap<String, Pair<String, Long>>()

    fun photoUrl(productId: String, file: String): String? {
        val now = System.currentTimeMillis()
        signed[file]?.let { (u, until) -> if (now < until) return u }
        val url = "$base/functions/v1/b2-media-url".toHttpUrl().newBuilder()
            .addQueryParameter("product_id", productId).addQueryParameter("file", file).build()
        val req = Request.Builder().url(url).header("apikey", key).get().build()
        return runCatching {
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return null
                JSONObject(r.body?.string().orEmpty()).getString("url").also { signed[file] = it to (now + 50 * 60_000L) }
            }
        }.getOrNull()
    }
}
