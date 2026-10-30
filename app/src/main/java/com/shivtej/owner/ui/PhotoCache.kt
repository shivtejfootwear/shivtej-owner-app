package com.shivtej.owner.ui

import android.content.Context
import com.shivtej.owner.data.Product
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Product photos kept on the phone. Each photo is downloaded once, as soon as it is in the product list
 * (on start, after every sync, and when a new photo is uploaded), so the screens show it at once
 * and never wait for the server. A photo name never changes (each upload gets a new name), so a saved photo is never stale.
 */
object PhotoCache {
    private val busy = HashSet<String>()

    private fun dir(ctx: Context) = File(ctx.applicationContext.filesDir, "photos").apply { mkdirs() }
    private fun fileOf(ctx: Context, name: String) = File(dir(ctx), name.replace(Regex("[^A-Za-z0-9._-]"), "_"))

    /** The saved photo for a "products/..." name, or null when it is not on the phone yet. */
    fun local(ctx: Context, name: String): File? = fileOf(ctx, name).takeIf { it.isFile && it.length() > 0 }

    /** Downloads one photo to the phone. Returns the saved file, or null when it could not be downloaded. */
    suspend fun download(ctx: Context, name: String): File? = withContext(Dispatchers.IO) {
        local(ctx, name)?.let { return@withContext it }
        synchronized(busy) { if (!busy.add(name)) return@withContext null }
        try {
            val url = privatePhotoUrl(name) ?: return@withContext null
            val f = fileOf(ctx, name)
            val tmp = File(f.parentFile, f.name + ".part")
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
            } finally { conn.disconnect() }
            if (tmp.length() > 0) { tmp.renameTo(f); f } else { tmp.delete(); null }
        } catch (e: Exception) {
            null
        } finally {
            synchronized(busy) { busy.remove(name) }
        }
    }

    /** How many product photos are on the phone, for the download button on the dashboard. */
    data class PhotoState(val total: Int, val saved: Int, val downloading: Boolean) {
        val missing: Int get() = (total - saved).coerceAtLeast(0)
    }
    private val _state = MutableStateFlow(PhotoState(0, 0, false))
    val state: StateFlow<PhotoState> = _state

    /** Downloads every product photo that is not on the phone yet; the saved ones are not touched. */
    suspend fun syncAll(ctx: Context, products: List<Product>) {
        val names = products.flatMap { it.allImages }.filter { it.startsWith("products/") }.distinct()
        val before = names.count { local(ctx, it) != null }
        _state.value = PhotoState(names.size, before, before < names.size)
        for (n in names) {
            if (local(ctx, n) != null) continue
            download(ctx, n)
        }
        _state.value = PhotoState(names.size, names.count { local(ctx, it) != null }, false)
    }
}
