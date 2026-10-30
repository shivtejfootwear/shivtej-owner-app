package com.shivtej.owner.ui

import android.content.Context
import com.shivtej.owner.data.Product
import com.shivtej.owner.data.titleOf
import com.shivtej.owner.net.Api
import com.shivtej.owner.net.ApiError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    // Only one photo is downloaded at a time. A second request for the same photo waits, then finds it saved.
    private val gate = Mutex()
    private val reasons = LinkedHashMap<String, String>()   // photos that failed in this run, with the reason
    private val failedAt = HashMap<String, Long>()          // when each failed photo was last tried
    private const val RETRY_AFTER = 10 * 60_000L

    private fun dir(ctx: Context) = File(ctx.applicationContext.filesDir, "photos").apply { mkdirs() }
    private fun fileOf(ctx: Context, name: String) = File(dir(ctx), name.replace(Regex("[^A-Za-z0-9._-]"), "_"))

    /** The saved photo for a "products/..." name, or null when it is not on the phone yet. */
    fun local(ctx: Context, name: String): File? = fileOf(ctx, name).takeIf { it.isFile && it.length() > 0 }

    private class Try(val reason: String?, val temporary: Boolean, val waitMs: Long = 0)

    /**
     * Downloads one photo. Returns null when it is saved on the phone, otherwise the reason it could not be saved.
     * A temporary error (server busy 503, too many requests 429, network dropped) is tried again up to [retries] times,
     * waiting 2 s, then 4 s (or the Retry-After time the server gives, up to 30 s).
     */
    suspend fun fetch(ctx: Context, name: String, retries: Int = 0): String? = gate.withLock {
        if (local(ctx, name) != null) return@withLock null
        var wait = 2000L
        var left = retries
        var last = fetchOnce(ctx, name)
        while (last.reason != null && last.temporary && left > 0) {
            kotlinx.coroutines.delay(maxOf(wait, last.waitMs))
            wait *= 2
            left--
            last = fetchOnce(ctx, name)
        }
        last.reason
    }

    private suspend fun fetchOnce(ctx: Context, name: String): Try = withContext(Dispatchers.IO) {
        if (local(ctx, name) != null) return@withContext Try(null, false)
        val pid = name.split("/").getOrNull(1) ?: return@withContext Try("फ़ाइल का नाम गलत है", false)
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
                conn.connectTimeout = 10_000
                conn.readTimeout = 20_000
                val f = fileOf(ctx, name)
                val tmp = File(f.parentFile, f.name + ".part")
                // Resume: a slow link keeps what was already received, the next try asks only for the rest.
                val have = if (tmp.isFile) tmp.length() else 0L
                if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
                val code = conn.responseCode
                if (code == 416) { tmp.delete(); return@withContext Try("फ़ोटो दोबारा शुरू हो रही है", true) }
                if (code != 200 && code != 206) {
                    val ra = (conn.getHeaderField("Retry-After")?.trim()?.toLongOrNull() ?: 0L) * 1000L
                    return@withContext Try("स्टोरेज से फ़ोटो नहीं आई (कोड $code)", code == 429 || code >= 500, ra.coerceAtMost(30_000L))
                }
                val appendMode = code == 206          // server sent only the rest
                conn.inputStream.use { inp -> java.io.FileOutputStream(tmp, appendMode).use { inp.copyTo(it) } }
                if (tmp.length() == 0L) { tmp.delete(); return@withContext Try("फ़ाइल खाली आई", true) }
                tmp.renameTo(f)
                Try(null, false)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Try("नेटवर्क टूटा (${e.javaClass.simpleName})", true)
        }
    }

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
        val later = ArrayList<String>()
        fun done(n: String, r: String?) {
            if (r == null) { saved++; reasons.remove(n); failedAt.remove(n) }
            else { reasons[n] = r; failedAt[n] = System.currentTimeMillis() }
        }
        // Pass 1: every missing photo, once. Failed ones wait for pass 2.
        for (n in names) {
            if (local(ctx, n) != null) continue
            val last = failedAt[n]
            if (last != null && System.currentTimeMillis() - last < RETRY_AFTER) continue   // tried recently
            val r = fetch(ctx, n, retries = 0)
            if (r != null) later.add(n)
            done(n, r)
            val pct = saved * 100 / total
            if (pct != lastPct || r != null) { lastPct = pct; publish(true) }   // a few updates only, so the screen does not lag
        }
        // Pass 2: the failed ones, with a couple of quick retries each.
        for (n in later) {
            if (local(ctx, n) != null) continue
            done(n, fetch(ctx, n, retries = 4))
            publish(true)
        }
        publish(false)
    }
}
