package com.shivtej.owner.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shivtej.owner.App
import com.shivtej.owner.net.Api
import com.shivtej.owner.sync.UploadNotices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** How long the "file went to ..." line stays on the card. */
private const val NOTICE_MS = 60_000L

/** Plan sizes for the bars. Change them here when the plan changes. */
object StorageLimits {
    const val B2_BYTES = 10L * 1024 * 1024 * 1024     // Backblaze B2: photos and videos (free 10 GB)
    const val DB_BYTES = 500L * 1024 * 1024           // Supabase database (free plan 500 MB)
    const val FILES_BYTES = 1024L * 1024 * 1024       // Supabase file storage (free plan 1 GB)
}

/** Supabase sizes: database bytes and file-storage bytes. Needs the owner code. */
private fun readSupabase(code: String): Pair<Long, Long> {
    val o = Api.rpc("owner_storage_usage", JSONObject().put("p_code", code)) as? JSONObject
        ?: throw IllegalStateException("no size returned")
    return Pair(o.optLong("db_bytes"), o.optLong("storage_bytes"))
}

/** A short Hindi/English reason for the red text, instead of the raw server message. */
private fun friendly(e: Throwable): String {
    val m = e.message.orEmpty()
    return when {
        m.contains("Could not find the function") || m.contains("schema cache") ->
            "Supabase में SQL update 12 अभी नहीं चला (owner_update_12_storage_usage.sql)"
        m.contains("INVALID_OWNER_CODE") -> "Owner code गलत है"
        m.contains("HTTP 404") || m.contains("USAGE_FAILED") || m.contains("NOT_FOUND") ->
            "b2-usage फ़ंक्शन Supabase पर अभी नहीं है, या Backblaze की चाबी में listFiles नहीं है"
        else -> "पढ़ नहीं पाया: ${m.ifBlank { "unknown error" }}"
    }
}

private fun sizeText(b: Long): String =
    if (b >= 1024L * 1024 * 1024) "%.2f GB".format(b / (1024.0 * 1024 * 1024))
    else "%.1f MB".format(b / (1024.0 * 1024))

@Composable
private fun StorageRow(label: String, bytes: Long?, limit: Long, error: String?) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
            if (bytes != null) Text("${sizeText(bytes)} of ${sizeText(limit)}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        if (bytes != null) {
            val frac = (bytes.toFloat() / limit.toFloat()).coerceIn(0f, 1f)
            LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        } else if (error != null) {
            Text(error, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** Storage card for the side panel: how full each storage is. The refresh icon reads them again. */
@Composable
fun StorageCard(app: App, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var dbBytes by remember { mutableStateOf<Long?>(null) }
    var filesBytes by remember { mutableStateOf<Long?>(null) }
    var supaError by remember { mutableStateOf<String?>(null) }
    var b2Bytes by remember { mutableStateOf<Long?>(null) }
    var b2Error by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        val code = app.prefs.ownerCode
        if (busy || code.isBlank()) return
        busy = true
        scope.launch {
            // the two reads are separate: if one is not set up yet, the other still shows
            val sb = withContext(Dispatchers.IO) { runCatching { readSupabase(code) } }
            val b2 = withContext(Dispatchers.IO) { runCatching { Api.b2Usage(code) } }
            sb.fold({ dbBytes = it.first; filesBytes = it.second; supaError = null },
                { dbBytes = null; filesBytes = null; supaError = friendly(it) })
            b2.fold({ b2Bytes = it; b2Error = null; app.prefs.b2UsedBytes = it }, { b2Bytes = null; b2Error = friendly(it) })
            busy = false
        }
    }
    LaunchedEffect(Unit) { refresh() }

    // The last file that was sent (B2 or Supabase). It is shown for one minute, then the line goes away by itself.
    val notice by UploadNotices.last.collectAsState()
    LaunchedEffect(notice) {
        val n = notice ?: return@LaunchedEffect
        delay(NOTICE_MS)
        UploadNotices.clear(n)
    }
    val shownNotice = notice?.takeIf { System.currentTimeMillis() - it.at < NOTICE_MS }

    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Storage, null)
                Spacer(Modifier.width(8.dp))
                Text("Storage", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = { refresh() }) { Icon(Icons.Filled.Refresh, "Refresh storage") }
            }
            if (busy) Text("Reading storage...", fontSize = 13.sp)
            StorageRow("Photos & videos (Backblaze B2)", b2Bytes, StorageLimits.B2_BYTES, b2Error)
            StorageRow("Database (Supabase)", dbBytes, StorageLimits.DB_BYTES, supaError)
            StorageRow("Files (Supabase)", filesBytes, StorageLimits.FILES_BYTES, supaError)
            shownNotice?.let { n ->
                Text("अभी ${n.fileKind} ${n.where} में चली गई", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
