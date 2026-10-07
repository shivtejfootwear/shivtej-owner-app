package com.shivtej.owner.net

import com.shivtej.owner.BuildConfig
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
    fun uploadImage(path: String, bytes: ByteArray, mime: String): String {
        val req = Request.Builder()
            .url("$base/storage/v1/object/product-images/$path")
            .header("apikey", key)
            .header("Content-Type", mime)
            .header("x-upsert", "true")
            .post(bytes.toRequestBody(mime.toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw fail(resp.code, text)
        }
        return "$base/storage/v1/object/public/product-images/$path"
    }
}
