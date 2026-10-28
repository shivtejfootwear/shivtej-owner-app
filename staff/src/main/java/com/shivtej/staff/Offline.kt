package com.shivtej.staff

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.util.concurrent.TimeUnit

/*
 * The catalog is kept on the phone, with every product photo downloaded once.
 *  - The app opens straight to the saved catalog and saved photos (no waiting, works without internet).
 *  - New products and new photos come in by themselves: every minute while the app is open,
 *    and about every 15 minutes in the background (Android's shortest background interval).
 *  - A photo is downloaded only once. A photo name never changes (each upload gets a new name),
 *    so a saved photo is never stale. Photos no longer in the catalog are removed from the phone.
 *  - On logout (or when the owner closes the access) the saved catalog and photos are deleted.
 */
object Offline {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val lock = Mutex()
    private val _photos = MutableStateFlow(0)
    /** Goes up each time a new photo is saved, so the screen shows it without a refresh. */
    val photosVersion: StateFlow<Int> = _photos

    private fun catalogFile(ctx: Context) = File(ctx.applicationContext.filesDir, "catalog.json")
    private fun photoDir(ctx: Context) = File(ctx.applicationContext.filesDir, "photos").apply { mkdirs() }
    private fun localName(name: String) = name.replace(Regex("[^A-Za-z0-9._-]"), "_")

    /** The saved photo for a "products/..." name, or null when it is not downloaded yet. */
    fun photoFile(ctx: Context, name: String): File? =
        File(photoDir(ctx), localName(name)).takeIf { it.isFile && it.length() > 0 }

    fun loadCatalog(ctx: Context): List<SProduct>? = runCatching {
        val f = catalogFile(ctx)
        if (!f.isFile) null else parseCatalog(JSONArray(f.readText()))
    }.getOrNull()

    private fun saveCatalog(ctx: Context, a: JSONArray) {
        val f = catalogFile(ctx)
        val tmp = File(f.parentFile, "catalog.json.tmp")
        tmp.writeText(a.toString())
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    /** Gets the catalog from the server and saves it on the phone. Throws NotAllowed when the access is closed. */
    suspend fun refresh(ctx: Context, token: String, device: String): List<SProduct> = withContext(Dispatchers.IO) {
        val a = Api.catalog(token, device)
        val list = parseCatalog(a)
        saveCatalog(ctx, a)
        list
    }

    private fun photoNames(products: List<SProduct>): List<Pair<String, String>> =
        products.flatMap { p -> (p.images + p.colours.flatMap { it.images }).map { p.id to it } }
            .filter { it.second.startsWith("products/") }
            .distinctBy { it.second }

    /** Downloads the photos that are not on the phone yet, and removes photos no longer in the catalog. */
    suspend fun syncPhotos(ctx: Context, products: List<SProduct>) = withContext(Dispatchers.IO) {
        if (!lock.tryLock()) return@withContext // a sync is already running
        try {
            val wanted = photoNames(products)
            val keep = wanted.map { localName(it.second) }.toSet()
            photoDir(ctx).listFiles()?.forEach { if (it.name !in keep) it.delete() }
            for ((pid, name) in wanted) {
                if (photoFile(ctx, name) != null) continue
                if (download(ctx, pid, name)) _photos.value = _photos.value + 1
            }
        } finally {
            lock.unlock()
        }
    }

    private fun download(ctx: Context, pid: String, name: String): Boolean = runCatching {
        val url = Api.photoUrl(pid, name) ?: return false
        http.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
            if (!r.isSuccessful) return false
            val body = r.body ?: return false
            val out = File(photoDir(ctx), localName(name))
            val tmp = File(out.parentFile, out.name + ".part")
            tmp.outputStream().use { body.byteStream().copyTo(it) }
            if (tmp.length() == 0L) { tmp.delete(); return false }
            tmp.renameTo(out)
        }
    }.getOrDefault(false)

    /** Deletes the saved catalog and photos and stops the background update (logout). */
    fun clear(ctx: Context) {
        runCatching { WorkManager.getInstance(ctx.applicationContext).cancelUniqueWork(WORK) }
        runCatching { catalogFile(ctx).delete() }
        runCatching { File(ctx.applicationContext.filesDir, "photos").deleteRecursively() }
    }

    private const val WORK = "catalog-sync"

    /** Downloads the full catalog and all photos now, in the background (after login, install update or phone restart). */
    fun syncNow(ctx: Context) {
        schedule(ctx)
        runCatching {
            val req = OneTimeWorkRequestBuilder<CatalogSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx.applicationContext)
                .enqueueUniqueWork("$WORK-now", ExistingWorkPolicy.REPLACE, req)
        }
    }

    /** Starts the background update (about every 15 minutes, only with internet). Safe to call many times. */
    fun schedule(ctx: Context) {
        runCatching {
            val req = PeriodicWorkRequestBuilder<CatalogSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx.applicationContext)
                .enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}

/** Background update: new catalog + new photos, while the app is closed. */
class CatalogSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val store = Store(applicationContext)
        val token = store.token ?: return Result.success()
        return try {
            val list = Offline.refresh(applicationContext, token, store.deviceId)
            Offline.syncPhotos(applicationContext, list)
            Result.success()
        } catch (e: NotAllowed) {
            store.logout(closedByOwner = true)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
