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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Plan sizes for the bars. Change them here when the plan changes. */
object StorageLimits {
    const val B2_BYTES = 10L * 1024 * 1024 * 1024     // Backblaze B2: photos and videos (free 10 GB)
    const val DB_BYTES = 500L * 1024 * 1024           // Supabase database (free plan 500 MB)
    const val FILES_BYTES = 1024L * 1024 * 1024       // Supabase file storage (free plan 1 GB)
}

class StorageUsage(val dbBytes: Long, val filesBytes: Long, val b2Bytes: Long)

/** Reads the three sizes. Both calls need the owner code; the Backblaze key stays on the server. */
private fun readStorage(code: String): StorageUsage {
    val sb = Api.rpc("owner_storage_usage", JSONObject().put("p_code", code)) as? JSONObject
        ?: throw IllegalStateException("no size returned")
    return StorageUsage(sb.optLong("db_bytes"), sb.optLong("storage_bytes"), Api.b2Usage(code))
}

private fun sizeText(b: Long): String =
    if (b >= 1024L * 1024 * 1024) "%.2f GB".format(b / (1024.0 * 1024 * 1024))
    else "%.1f MB".format(b / (1024.0 * 1024))

@Composable
private fun StorageBar(label: String, used: Long, limit: Long) {
    val frac = (used.toFloat() / limit.toFloat()).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text("${sizeText(used)} of ${sizeText(limit)}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
    }
}

/** Dashboard card: how full each storage is. The refresh icon reads the sizes again. */
@Composable
fun StorageCard(app: App) {
    val scope = rememberCoroutineScope()
    var usage by remember { mutableStateOf<StorageUsage?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun refresh() {
        val code = app.prefs.ownerCode
        if (busy || code.isBlank()) return
        busy = true
        error = null
        scope.launch {
            try {
                usage = withContext(Dispatchers.IO) { readStorage(code) }
            } catch (e: Exception) {
                error = "Could not read storage: ${e.message ?: "unknown error"}"
            } finally {
                busy = false
            }
        }
    }
    LaunchedEffect(Unit) { refresh() }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Storage, null)
                Spacer(Modifier.width(8.dp))
                Text("Storage", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = { refresh() }) { Icon(Icons.Filled.Refresh, "Refresh storage") }
            }
            val u = usage
            when {
                u != null -> {
                    StorageBar("Photos & videos (Backblaze B2)", u.b2Bytes, StorageLimits.B2_BYTES)
                    StorageBar("Database (Supabase)", u.dbBytes, StorageLimits.DB_BYTES)
                    StorageBar("Files (Supabase)", u.filesBytes, StorageLimits.FILES_BYTES)
                }
                busy -> Text("Reading storage...", fontSize = 13.sp)
                else -> Text("Tap refresh to read storage.", fontSize = 13.sp)
            }
            error?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
        }
    }
}
