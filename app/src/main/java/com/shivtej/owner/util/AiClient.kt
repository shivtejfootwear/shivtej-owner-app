package com.shivtej.owner.util

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Optional online answers (Anthropic Messages API). Used only when the owner turned it on in Settings,
 * the phone is online and an API key is saved. Any failure returns a Result.failure with a short reason,
 * and the caller then shows the offline answer instead.
 * The key is passed in from [SecretStore]; it is never written to source code, logs or the sync queue.
 */
object AiClient {
    private const val URL = "https://api.anthropic.com/v1/messages"
    private const val VERSION = "2023-06-01"
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private const val SYSTEM =
        "You are the assistant of a footwear shop owner. Answer briefly, in the same language as the question " +
            "(English, Hinglish or Hindi). Use ONLY the business summary you are given. If the summary does not " +
            "contain the answer, say that this information is not on the phone's summary. Do not invent numbers."

    fun ask(apiKey: String, model: String, question: String, summary: String): Result<String> = runCatching {
        require(apiKey.isNotBlank()) { "No API key is saved. Add it in Settings > AI assistant." }
        val body = JSONObject()
            .put("model", model.ifBlank { "claude-haiku-5-5" })
            .put("max_tokens", 700)
            .put("system", SYSTEM)
            .put("messages", JSONArray().put(
                JSONObject().put("role", "user")
                    .put("content", "Business summary:\n$summary\n\nQuestion: $question")
            ))
        val req = Request.Builder()
            .url(URL)
            .header("x-api-key", apiKey)
            .header("anthropic-version", VERSION)
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException(when (resp.code) {
                    401, 403 -> "the API key was not accepted. Check it in Settings."
                    404 -> "the model name was not found. Check it in Settings."
                    429 -> "too many requests right now. Try again later."
                    in 500..599 -> "the AI service is having trouble (${resp.code})."
                    else -> "the AI service returned an error (${resp.code})."
                })
            }
            val parts = JSONObject(text).optJSONArray("content") ?: throw IllegalStateException("the answer was empty.")
            val answer = (0 until parts.length())
                .map { parts.getJSONObject(it) }
                .filter { it.optString("type") == "text" }
                .joinToString("") { it.optString("text") }
                .trim()
            if (answer.isEmpty()) throw IllegalStateException("the answer was empty.")
            answer
        }
    }
}
