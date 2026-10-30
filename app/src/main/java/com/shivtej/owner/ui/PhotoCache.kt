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
    private val failedAt = HashMap<String, Long>()          // when each failed photo was last tried
    private const val RETRY_AFTER = 10 * 60_000L

    private fun dir(ctx: Context) = File(ctx.applicationContext.filesDir, "photos").apply { mkdirs() }
    private fun fileOf(ctx: Context, name: String) = File(dir(ctx), name.replace(Regex("[^A-Za-z0-9._-]"), "_"))

    /** The saved photo for a "products/..." name, or null when it is not on the phone yet. */
    fun local(ctx: Context, name: String): File? = fileOf(ctx, name).takeIf { it.isFile && it.length() > 0 }

    private class Try(val reason: String?, val temporary: Boolean)

    /** Downloads one photo. Returns null when it is saved on the phone, otherwise the reason it could not be saved.
     *  Temporary problems (server busy, network dropped) are tried again a few times, after a short wait. */
    suspend fun fetch(ctx: Context, name: String): String? {
        if (local(ctx, name) != null) return null
        var last: Try = Try("अभी डाउनलोड चल रहा है", false)
        for (i in 0 until 4) {
            last = fetchOnce(ctx, name)
            if (last.reason == null || !last.temporary) break
            kotlinx.coroutines.delay(2000L * (i + 1))
        }
        return last.reason
    }

    private suspend fun fetchOnce(ctx: Context, name: String): Try = withContext(Dispatchers.IO) {
        if (local(ctx, name) != null) return@withContext Try(null, false)
        val pid = name.split("/").getOrNull(1) ?: return@withContext Try("फ़ाइल का नाम गलत है", false)
        synchronized(busy) { if (!busy.add(name)) return@withContext Try("अभी डाउनलोड चल रहा है", false) }
        try {
            val url = try {
                Api.productPhotoUrl(pid, name)
            } catch (e: Exception) {
                val st = (e as? ApiError)?.status ?: 0
                return@withContext when (st) {
                    404 -> Try("सर्वर पर यह फ़ोटो इस प्रोडक्ट से जुड़ी नहीं मिली", false)
                    400 -> Try("सर्वर ने फ़ाइल का नाम नहीं माना", false)
                    0 -> Try("इंटरनेट या सर्वर की दिक्कत", true)
                    else -> Try("सर्वर ने लिंक नहीं दिया (कोड $st)", st >= 500)
                }
            }
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                val code = conn.responseCode
                if (code != 200) return@withContext Try("स्टोरेज से फ़ोटो नहीं आई (कोड $code)", code == 429 || code >= 500)
                val f = fileOf(ctx, name)
                val tmp = File(f.parentFile, f.name + ".part")
                conn.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
                if (tmp.length() == 0L) { tmp.delete(); return@withContext Try("फ़ाइल खाली आई", true) }
                tmp.renameTo(f)
                Try(null, false)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Try("नेटवर्क टूटा (${e.javaClass.simpleName})", true)
        } finally {
            synchronized(busy) { busy.remove(name) }
        }
    }

    /** The saved photo for a name: downloads it now if it is not on the phone yet. Null when it could not be saved. */

    /**
     * Downloads every product photo that is not on the phone yet.
     * retry = true tries again the photos that failed before; otherwise they are not asked for again on every change.
     */
    suspend fun syncAll(ctx: Context, products: List<Product>, retry: Boolean = false) {
        if (retry) { reasons.clear(); failedAt.clear() }
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
            val last = failedAt[n]
            if (last != null && System.currentTimeMillis() - last < RETRY_AFTER) continue   // tried recently
            val r = fetch(ctx, n)
            if (r == null) { saved++; reasons.remove(n); failedAt.remove(n) }
            else { reasons[n] = r; failedAt[n] = System.currentTimeMillis() }
            val pct = saved * 100 / total
            if (pct != lastPct || r != null) { lastPct = pct; publish(true) }   // a few updates only, so the screen does not lag
        }
        publish(false)
    }
}
