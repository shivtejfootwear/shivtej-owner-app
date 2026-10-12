package com.shivtej.owner.net

import com.shivtej.owner.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.TimeUnit

class ApiError(val status: Int, val code: String?, message: String) : Exception(message) {
    val isAuth get() = message?.contains("INVALID_OWNER_CODE") == true
}

object Api {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val base get() = BuildConfig.SUPABASE_URL
    private val key get() = BuildConfig.SUPABASE_KEY

    private fun fail(status: Int, text: String): ApiError {
        var msg = text
        var code: String? = null
        try {
            val o = JSONObject(text)
            msg = o.optString("message", text)
            code = o.optString("code").ifBlank { null }
        } catch (_: Exception) { }
        return ApiError(status, code, msg.ifBlank { "HTTP $status" })
    }

    /** Calls a Postgres function through PostgREST. Returns JSONArray / JSONObject / String / Number / null. */
    fun rpc(fn: String, args: JSONObject): Any? {
        val req = Request.Builder()
            .url("$base/rest/v1/rpc/$fn")
            .header("apikey", key)
            .header("Content-Type", "application/json")
            .post(args.toString().toRequestBody(JSON))
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw fail(resp.code, text)
            return if (text.isBlank()) null else JSONTokener(text).nextValue()
        }
    }

    /** Uploads one image to the public "product-images" bucket and returns its public URL. */
    fun uploadImage(path: String, bytes: ByteArray, mime: String): String = uploadFile("product-images", path, bytes, mime)

    /** Uploads one file (image or PDF) to a public bucket and returns its public URL. */
    fun uploadFile(bucket: String, path: String, bytes: ByteArray, mime: String): String {
        val req = Request.Builder()
            .url("$base/storage/v1/object/$bucket/$path")
            .header("apikey", key)
            .header("Content-Type", mime)
            .header("x-upsert", "true")
            .post(bytes.toRequestBody(mime.toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw fail(resp.code, text)
        }
        return "$base/storage/v1/object/public/$bucket/$path"
    }

    private val functionsBase get() = "$base/functions/v1"

    /**
     * Uploads one website photo (slot "hero", "band" or "heroart") through the owner-only site-media-upload function.
     * Returns its public URL, which the Admin Portal saves in the website settings.
     */
    fun uploadSiteMedia(code: String, slot: String, bytes: ByteArray, mime: String): String {
        val req = Request.Builder()
            .url("$functionsBase/site-media-upload")
            .header("apikey", key)
            .header("x-owner-code", code)
            .header("x-slot", slot)
            .header("Content-Type", mime)
            .post(bytes.toRequestBody(mime.toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw fail(resp.code, text)
            return JSONObject(text).getString("url")
        }
    }

    /**
     * Sends one product photo to the PRIVATE B2 bucket through the owner-only b2-upload function.
     * Returns the object name (for example "products/<id>/<uuid>.jpg") that is saved in products.images.
     */
    fun uploadProductPhoto(code: String, productId: String, bytes: ByteArray, mime: String): String {
        val req = Request.Builder()
            .url("$functionsBase/b2-upload")
            .header("apikey", key)
            .header("x-owner-code", code)
            .header("x-kind", "image")
            .header("x-product-id", productId)
            .header("Content-Type", mime)
            .post(bytes.toRequestBody(mime.toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw fail(resp.code, text)
            return JSONObject(text).getString("file_name")
        }
    }

    /** Asks the server for a one-hour signed URL of one private product photo. Needs no owner code. */
    fun productPhotoUrl(productId: String, fileName: String): String {
        val url = "$functionsBase/b2-media-url".toHttpUrl().newBuilder()
            .addQueryParameter("product_id", productId)
            .addQueryParameter("file", fileName)
            .build()
        val req = Request.Builder().url(url).header("apikey", key).get().build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw fail(resp.code, text)
            return JSONObject(text).getString("url")
        }
    }

    // ---- Supabase admin login, needed only for Shiprocket shipping (the server checks the admin list) ----

    /** Logs in with the website admin email and password. Returns the session (access_token, refresh_token, expires_in). */
    fun adminSignIn(email: String, password: String): JSONObject =
        authToken("password", JSONObject().put("email", email).put("password", password))

    /** Renews a login with its refresh token. Returns a new session. */
    fun adminRefresh(refreshToken: String): JSONObject =
        authToken("refresh_token", JSONObject().put("refresh_token", refreshToken))

    private fun authToken(grant: String, body: JSONObject): JSONObject {
        val req = Request.Builder()
            .url("$base/auth/v1/token?grant_type=$grant")
            .header("apikey", key)
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON))
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ApiError(resp.code, "LOGIN_FAILED", "LOGIN_FAILED")
            return JSONObject(text)
        }
    }

    /**
     * Calls the shiprocket-ship function with a logged-in admin token.
     * Body: {"action":"couriers","order_id":...} to list couriers, or {"order_id":...,"courier_id":...} to create the shipment.
     * On an error the ApiError code is the server code (for example NOT_READY or NOT_ADMIN).
     */
    fun shiprocketCall(token: String, body: JSONObject): JSONObject {
        val req = Request.Builder()
            .url("$functionsBase/shiprocket-ship")
            .header("apikey", key)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON))
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val serverCode = try { JSONObject(text).optString("error") } catch (_: Exception) { "" }
                throw ApiError(resp.code, serverCode.ifBlank { null }, serverCode.ifBlank { "HTTP ${resp.code}" })
            }
            return JSONObject(text)
        }
    }
}
