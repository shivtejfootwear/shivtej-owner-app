package com.shivtej.owner.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID

/**
 * Product videos chosen from the phone's gallery. A copy is kept in the app's private folder, so the file stays
 * available offline and is uploaded later by the sync. Videos above [MAX_BYTES] are refused (the server limit is the same).
 */
object Videos {
    const val MAX_BYTES = 25L * 1024 * 1024
    const val TOO_BIG = "This video is bigger than 25 MB. Choose a shorter or smaller video."

    /** Copies the video into the app folder. Returns the local path, or null when the file can not be read. */
    fun importUri(ctx: Context, uri: Uri): String? {
        val reported = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
        } ?: -1L
        if (reported > MAX_BYTES) throw IllegalArgumentException(TOO_BIG)
        val input = ctx.contentResolver.openInputStream(uri) ?: return null
        val ext = when (ctx.contentResolver.getType(uri)) {
            "video/quicktime" -> "mov"
            "video/webm" -> "webm"
            else -> "mp4"
        }
        val dir = File(ctx.filesDir, "videos").apply { mkdirs() }
        val out = File(dir, UUID.randomUUID().toString() + "." + ext)
        var copied = 0L
        try {
            input.use { src ->
                out.outputStream().use { dst ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = src.read(buf)
                        if (n <= 0) break
                        copied += n
                        if (copied > MAX_BYTES) throw IllegalArgumentException(TOO_BIG)
                        dst.write(buf, 0, n)
                    }
                }
            }
        } catch (e: Exception) {
            out.delete()
            throw e
        }
        return if (copied > 0L) out.absolutePath else { out.delete(); null }
    }
}
