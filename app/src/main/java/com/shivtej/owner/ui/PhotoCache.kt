package com.shivtej.owner.ui

import android.content.Context
import com.shivtej.owner.data.Product
import com.shivtej.owner.data.titleOf
import com.shivtej.owner.net.Api
import com.shivtej.owner.net.ApiError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Product photos kept on the phone. Each photo is downloaded once, as soon as it is in the product list
 * (on start, after every sync, and when a new photo is uploaded), so the screens show it at once.
 * A photo name never changes (each upload gets a new name), so a saved photo is never stale.
 * A photo that cannot be downloaded is listed with the reason, and is tried again only with "दोबारा कोशिश करें".
 */
object PhotoCache {
    /** A photo that is not on the phone: its file name, the item it belongs to, and why it failed. */
    data class PhotoFail(val name: String, val item: String, val reason: String)

    /** What the dashboard button and its dialog show. percent is 0..100. */
    data class PhotoState(val total: Int, val saved: Int, val downloading: Boolean, val percent: Int, val fails: List<PhotoFail>) {
        val missing: Int get() = (total - saved).coerceAtLeast(0)
    }

    private val _state = MutableStateFlow(PhotoState(0, 0, false, 100, emptyList()))
    val state: StateFlow<PhotoState> = _state

    private val busy = HashSet<String>()
    private val reasons = LinkedHashMap<String, String>()   // photos that failed in this run, with the reason

    private fun dir(ctx: Context) = File(ctx.applicationContext.filesDir, "photos").apply { mkdirs() }
    private fun fileOf(ctx: Context, name: String) = File(dir(ctx), name.replace(Regex("[^A-Za-z0-9._-]"), "_"))

    /** The saved photo for a "products/..." name, or null when it is not on the phone yet. */
    fun local(ctx: Context, name: String): File? = fileOf(ctx, name).takeIf { it.isFile && it.length() > 0 }

    /** Downloads one photo. Returns null when it is saved on the phone, otherwise the reason it could not be saved. */
    suspend fun fetch(ctx: Context, name: String): String? = withContext(Dispatchers.IO) {
        if (local(ctx, name) != null) return@withContext null
        val pid = name.split("/").getOrNull(1) ?: return@withContext "फ़ाइल का नाम गलत है"
        synchronized(busy) { if (!busy.add(name)) return@withContext "अभी डाउनलोड चल रहा है" }
        try {
            val url = try {
                Api.productPhotoUrl(pid, name)
            } catch (e: Exception) {
                return@withContext when ((e as? ApiError)?.status ?: 0) {
                    404 -> "सर्वर पर यह फ़ोटो इस प्रोडक्ट से जुड़ी नहीं मिली"
                    400 -> "सर्वर ने फ़ाइल का नाम नहीं माना"
                    0 -> "इंटरनेट या सर्वर की दिक्कत"
                    else -> "सर्वर ने लिंक नहीं दिया"
                }
            }
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                val code = conn.responseCode
                if (code != 200) return@withContext "स्टोरेज में फ़ाइल नहीं मिली (कोड $code)"
                val f = fileOf(ctx, name)
                val tmp = File(f.parentFile, f.name + ".part")
                conn.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
                if (tmp.length() == 0L) { tmp.delete(); return@withContext "फ़ाइल खाली आई" }
                tmp.renameTo(f)
                null
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            "डाउनलोड टूटा (${e.javaClass.simpleName})"
        } finally {
            synchronized(busy) { busy.remove(name) }
        }
    }

    /**
     * Downloads every product photo that is not on the phone yet.
     * retry = true tries again the photos that failed before; otherwise they are not asked for again on every change.
     */
    suspend fun syncAll(ctx: Context, products: List<Product>, retry: Boolean = false) {
        if (retry) reasons.clear()
        val item = LinkedHashMap<String, String>()
        products.forEach { p ->
            val t = titleOf(p.brand, p.article, p.name)
            p.allImages.forEach { if (it.startsWith("products/")) item.putIfAbsent(it, t) }
        }
        val names = item.keys.toList()
        val total = names.size
        var saved = names.count { local(ctx, it) != null }

        fun publish(downloading: Boolean) {
            val pct = if (total == 0) 100 else saved * 100 / total
            val fails = reasons.entries
                .filter { item.containsKey(it.key) && local(ctx, it.key) == null }
                .map { PhotoFail(it.key, item[it.key].orEmpty(), it.value) }
            _state.value = PhotoState(total, saved, downloading, pct, fails)
        }

        publish(true)
        var lastPct = if (total == 0) 100 else saved * 100 / total
        for (n in names) {
            if (local(ctx, n) != null) continue
            if (reasons.containsKey(n)) continue          // failed before: waits for "दोबारा कोशिश करें"
            val r = fetch(ctx, n)
            if (r == null) { saved++; reasons.remove(n) } else reasons[n] = r
            val pct = saved * 100 / total
            if (pct != lastPct || r != null) { lastPct = pct; publish(true) }   // a few updates only, so the screen does not lag
        }
        publish(false)
    }
}
