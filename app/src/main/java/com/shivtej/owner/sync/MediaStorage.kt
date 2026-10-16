package com.shivtej.owner.sync

import com.shivtej.owner.data.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where product photos and videos are kept. */
object MediaStorage {
    /**
     * Free Backblaze B2 is 10 GB. New photos and videos go to B2 only while the last known size is below this.
     * Above it, new files go to Supabase Storage. Backblaze does not block uploads by itself, so this keeps us within the free space.
     */
    const val B2_SWITCH_BYTES = 9L * 1024 * 1024 * 1024

    /** True while B2 is below the switch limit (the size is the one the storage card read last). */
    fun b2Available(prefs: Prefs): Boolean = prefs.b2UsedBytes < B2_SWITCH_BYTES
}

/** One file that was just sent to a storage. The storage card shows it for one minute. */
data class UploadNotice(val where: String, val fileKind: String, val at: Long)

object UploadNotices {
    private val _last = MutableStateFlow<UploadNotice?>(null)
    val last: StateFlow<UploadNotice?> = _last.asStateFlow()

    /** where: "Backblaze B2" or "Supabase Storage". fileKind: "फ़ोटो" or "वीडियो". */
    fun set(where: String, fileKind: String) {
        _last.value = UploadNotice(where, fileKind, System.currentTimeMillis())
    }

    fun clear(n: UploadNotice) {
        if (_last.value == n) _last.value = null
    }
}
