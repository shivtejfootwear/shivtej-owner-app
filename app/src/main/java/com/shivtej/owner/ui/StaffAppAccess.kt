@file:OptIn(ExperimentalMaterial3Api::class)

package com.shivtej.owner.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shivtej.owner.App
import com.shivtej.owner.data.StaffE
import com.shivtej.owner.net.Api
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/*
 * Owner app > Staff > "Staff app": who may use the Shivtej Staff app (catalog), and where they were in shop hours.
 *  - Give access: a 6-digit code for one staff member (works once, 24 hours, one phone).
 *  - Close access: the staff app logs out at once and can not open the catalog again (a new code gives access again).
 *  - Location: last point and today's points, opened in Google Maps. Only shop hours are recorded.
 */

private data class AppAccess(
    val staffId: String, val active: Boolean, val hasDevice: Boolean, val deviceName: String,
    val codePending: Boolean, val codeExpires: String, val lastSeen: String,
    val lat: Double?, val lng: Double?, val battery: Int?, val status: String, val at: String,
    val intervalSec: Int
)

/** How often the location is sent (seconds) -> label. Live = every 15 seconds. */
private val INTERVALS = listOf(15 to "Live (every 15 sec)", 60 to "Every 1 min", 300 to "Every 5 min", 600 to "Every 10 min",
    1800 to "Every 30 min", 3600 to "Every 1 hour", 7200 to "Every 2 hours")
private fun intervalLabel(sec: Int) = INTERVALS.firstOrNull { it.first == sec }?.second ?: "Every ${sec / 60} min"

private fun JSONObject.dbl(k: String): Double? = if (isNull(k) || !has(k)) null else optDouble(k).takeIf { !it.isNaN() }
private fun JSONObject.txt(k: String): String = if (isNull(k)) "" else optString(k, "")

private fun parseAccess(o: JSONObject) = AppAccess(
    o.txt("staff_id"), o.optBoolean("active"), o.optBoolean("has_device"), o.txt("device_name"),
    o.optBoolean("code_pending"), o.txt("code_expires"), o.txt("last_seen"),
    o.dbl("lat"), o.dbl("lng"), if (o.isNull("battery")) null else o.optInt("battery"), o.txt("status"), o.txt("at"),
    o.optInt("interval_sec", 600)
)

private val timeFmt = DateTimeFormatter.ofPattern("h:mm a")
private val dayTimeFmt = DateTimeFormatter.ofPattern("d MMM, h:mm a")

private fun whenText(iso: String): String {
    if (iso.isBlank()) return ""
    return try {
        val t = OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault())
        val mins = (System.currentTimeMillis() - t.toInstant().toEpochMilli()) / 60_000
        val ago = when {
            mins < 1 -> "just now"
            mins < 60 -> "$mins min ago"
            mins < 24 * 60 -> "${mins / 60} h ago"
            else -> "${mins / (24 * 60)} days ago"
        }
        val day = if (t.toLocalDate() == LocalDate.now()) t.format(timeFmt) else t.format(dayTimeFmt)
        "$day ($ago)"
    } catch (e: Exception) { iso }
}

private fun mapUrl(lat: Double, lng: Double) = "https://www.google.com/maps/search/?api=1&query=$lat,$lng"

private fun minText(m: Int): String {
    val h = m / 60; val mm = m % 60
    return "%d:%02d %s".format(if (h % 12 == 0) 12 else h % 12, mm, if (h < 12) "AM" else "PM")
}

@Composable
fun StaffAppTab(app: App) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val staff by app.repo.staff.collectAsStateWithLifecycle(emptyList())
    val code = app.prefs.ownerCode
    var access by remember { mutableStateOf<Map<String, AppAccess>>(emptyMap()) }
    var startMin by remember { mutableIntStateOf(540) }
    var endMin by remember { mutableIntStateOf(1320) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var newCode by remember { mutableStateOf<Pair<String, String>?>(null) }   // staff name to code
    var closing by remember { mutableStateOf<StaffE?>(null) }
    var trailOf by remember { mutableStateOf<StaffE?>(null) }
    var editHours by remember { mutableStateOf(false) }

    fun msg(e: Exception): String {
        val m = e.message.orEmpty()
        return when {
            m.contains("INVALID_OWNER_CODE") -> "Owner code is wrong. Check it in Settings."
            m.contains("BAD_MOBILE") -> "Add a correct 10-digit mobile number to this staff first."
            m.contains("BAD_HOURS") -> "Start and end time can not be the same."
            m.contains("NO_ACCESS") -> "Give app access to this staff first."
            m.contains("Could not find the function") || m.contains("PGRST202") ->
                "Run owner_update_15_staff_app.sql in Supabase first."
            else -> "No internet or server problem. Try again."
        }
    }

    fun load(quiet: Boolean = false) = scope.launch {
        if (!quiet) { loading = true; error = null }
        try {
            val r = withContext(Dispatchers.IO) { Api.rpc("owner_staff_app_list", JSONObject().put("p_code", code)) } as JSONObject
            r.optJSONObject("hours")?.let { startMin = it.optInt("start_min", 540); endMin = it.optInt("end_min", 1320) }
            val a = r.optJSONArray("staff") ?: JSONArray()
            access = (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::parseAccess) }.associateBy { it.staffId }
        } catch (e: Exception) { if (!quiet) error = msg(e) } finally { loading = false }
    }
    LaunchedEffect(Unit) { load() }
    // keeps the locations fresh while this screen is open: every 15 seconds when someone is on Live, else every minute
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(if (access.values.any { it.active && it.intervalSec <= 15 }) 15_000L else 60_000L)
            load(quiet = true)
        }
    }

    fun setInterval(s: StaffE, sec: Int) = scope.launch {
        try {
            withContext(Dispatchers.IO) {
                Api.rpc("owner_staff_app_interval", JSONObject().put("p_code", code).put("p_staff_id", s.id).put("p_sec", sec))
            }
            toast(ctx, "${s.name}: ${intervalLabel(sec)}. The phone switches within about 2 minutes.")
            load(quiet = true)
        } catch (e: Exception) { toast(ctx, msg(e)) }
    }

    fun giveCode(s: StaffE) = scope.launch {
        try {
            val r = withContext(Dispatchers.IO) {
                Api.rpc("owner_staff_app_code", JSONObject().put("p_code", code).put("p_staff_id", s.id)
                    .put("p_name", s.name).put("p_mobile", s.mobile))
            } as JSONObject
            newCode = s.name to r.getString("code")
            load()
        } catch (e: Exception) { toast(ctx, msg(e)) }
    }

    fun open(url: String) { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }

    val people = staff.filter { it.active || access[it.id]?.let { a -> a.active } == true }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Who can use the Shivtej Staff app (catalog with MRP only). Their location is saved only in shop hours.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Schedule, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("  Shop hours: ${minText(startMin)} – ${minText(endMin)}", fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { editHours = true }, enabled = !loading && error == null) { Text("Change") }
                    IconButton(onClick = { load() }) { Icon(Icons.Filled.Refresh, "Refresh") }
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                if (people.isEmpty()) EmptyState("No active staff. Add staff in the Staff tab first.")
            }
        }
        items(people, key = { it.id }) { s ->
            val a = access[s.id]
            Card(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, fontWeight = FontWeight.Bold)
                            Text(s.mobile.ifBlank { "No mobile number" }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val (label, colour) = when {
                            a == null -> "No access" to MaterialTheme.colorScheme.onSurfaceVariant
                            !a.active -> "Closed" to RedOut
                            a.hasDevice -> "Logged in" to GreenOk
                            a.codePending -> "Code given" to AmberLow
                            else -> "No access" to MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Text(label, color = colour, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    if (a != null && a.active && a.hasDevice) Text("Phone: ${a.deviceName.ifBlank { "-" }} · app opened ${whenText(a.lastSeen)}", fontSize = 12.sp)
                    if (a != null && a.active && a.codePending && !a.hasDevice) Text("Waiting for login. Code works until ${whenText(a.codeExpires).substringBefore(" (")}.", fontSize = 12.sp)
                    if (a != null && a.at.isNotBlank()) {
                        val line = when (a.status) {
                            "gps_off" -> "GPS was OFF · ${whenText(a.at)}"
                            "no_permission" -> "Location permission taken off · ${whenText(a.at)}"
                            else -> "Last location ${whenText(a.at)}"
                        } + (a.battery?.let { " · battery $it%" } ?: "")
                        Text(line, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            color = if (a.status == "ok") MaterialTheme.colorScheme.onSurface else RedOut)
                    }
                    if (a != null && a.active) {
                        var menu by remember { mutableStateOf(false) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Location: ", fontSize = 13.sp)
                            Box {
                                AssistChip(
                                    onClick = { menu = true },
                                    label = { Text(intervalLabel(a.intervalSec), fontWeight = FontWeight.Bold,
                                        color = if (a.intervalSec <= 15) RedOut else Color.Unspecified) },
                                    trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) }
                                )
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    INTERVALS.forEach { (sec, label) ->
                                        DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; setInterval(s, sec) })
                                    }
                                }
                            }
                            if (a.intervalSec <= 15) Text("  ● LIVE", color = RedOut, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                        if (a?.lat != null && a.lng != null) OutlinedButton(onClick = { open(mapUrl(a.lat, a.lng)) }) {
                            Icon(Icons.Filled.Place, null, Modifier.size(18.dp)); Text(" Map")
                        }
                        if (a != null) OutlinedButton(onClick = { trailOf = s }) { Text("Today") }
                        if (a != null && a.active) TextButton(onClick = { closing = s }) { Text("Close access", color = RedOut) }
                    }
                    Button(
                        onClick = { giveCode(s) }, enabled = !loading && error == null,
                        colors = ButtonDefaults.buttonColors(containerColor = BrandRed), modifier = Modifier.fillMaxWidth()
                    ) { Text(if (a == null || !a.active) "Give app access (make code)" else "Make a new code (old phone logs out)") }
                }
            }
        }
    }

    newCode?.let { (name, c) ->
        AlertDialog(
            onDismissRequest = { newCode = null },
            title = { Text("Code for $name") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(c.chunked(3).joinToString(" "), fontSize = 36.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 4.sp)
                    Text("Tell this code to $name. In the Shivtej Staff app they type their mobile number and this code. " +
                        "It works once, for 24 hours, on one phone. 5 wrong tries and it stops working.", fontSize = 13.sp)
                }
            },
            confirmButton = { TextButton(onClick = { newCode = null }) { Text("Done") } }
        )
    }

    closing?.let { s ->
        ConfirmDialog(
            "Close app access?",
            "${s.name}'s Shivtej Staff app will log out and the catalog will not open again. You can give a new code later.",
            "Close access",
            onConfirm = {
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { Api.rpc("owner_staff_app_block", JSONObject().put("p_code", code).put("p_staff_id", s.id)) }
                        toast(ctx, "Access closed for ${s.name}.")
                        load()
                    } catch (e: Exception) { toast(ctx, msg(e)) }
                }
            },
            onDismiss = { closing = null }
        )
    }

    trailOf?.let { s -> TrailDialog(app, s, ::open) { trailOf = null } }

    if (editHours) HoursDialog(startMin, endMin, onDismiss = { editHours = false }) { st, en ->
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    Api.rpc("owner_staff_app_hours", JSONObject().put("p_code", code).put("p_start_min", st).put("p_end_min", en))
                }
                editHours = false; toast(ctx, "Shop hours saved."); load()
            } catch (e: Exception) { toast(ctx, msg(e)) }
        }
    }
}

/** Location points of one day, with a Map link for each. */
@Composable
private fun TrailDialog(app: App, s: StaffE, open: (String) -> Unit, onClose: () -> Unit) {
    var day by remember { mutableStateOf(LocalDate.now()) }
    var points by remember { mutableStateOf<List<JSONObject>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(day) {
        points = null; error = null
        try {
            val a = withContext(Dispatchers.IO) {
                Api.rpc("owner_staff_app_trail", JSONObject().put("p_code", app.prefs.ownerCode).put("p_staff_id", s.id)
                    .put("p_day", day.toString()))
            } as? JSONArray ?: JSONArray()
            // Live saves a point every 15 seconds: the list shows at most one point per minute (GPS-off rows always)
            var last = 0L
            points = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { p ->
                val t = runCatching { OffsetDateTime.parse(p.optString("at")).toInstant().toEpochMilli() }.getOrDefault(0L)
                val keep = p.optString("status") != "ok" || t - last >= 60_000L
                if (keep) last = t
                keep
            }
        } catch (e: Exception) { error = "Could not load. Check the internet." }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Column {
                Text(s.name, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { day = day.minusDays(1) }) { Icon(Icons.Filled.ChevronLeft, "Previous day") }
                    Text(if (day == LocalDate.now()) "Today" else day.format(DateTimeFormatter.ofPattern("d MMM yyyy")),
                        fontSize = 15.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = { day = day.plusDays(1) }, enabled = day.isBefore(LocalDate.now())) {
                        Icon(Icons.Filled.ChevronRight, "Next day")
                    }
                }
            }
        },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val list = points
                when {
                    error != null -> Text(error ?: "", color = MaterialTheme.colorScheme.error)
                    list == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    list.isEmpty() -> Text("No location saved on this day.")
                    else -> list.forEach { p ->
                        val t = runCatching {
                            OffsetDateTime.parse(p.optString("at")).atZoneSameInstant(ZoneId.systemDefault()).format(timeFmt)
                        }.getOrDefault("")
                        val lat = p.dbl("lat"); val lng = p.dbl("lng")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(t, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(78.dp))
                            Text(when (p.optString("status")) {
                                "gps_off" -> "GPS OFF"
                                "no_permission" -> "Permission off"
                                else -> if (p.isNull("acc")) "Location" else "Location (±${p.optInt("acc")} m)"
                            }, fontSize = 13.sp, modifier = Modifier.weight(1f),
                                color = if (p.optString("status") == "ok") Color.Unspecified else RedOut)
                            if (lat != null && lng != null) TextButton(onClick = { open(mapUrl(lat, lng)) }) { Text("Map") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } }
    )
}

@Composable
private fun HoursDialog(start: Int, end: Int, onDismiss: () -> Unit, onSave: (Int, Int) -> Unit) {
    val st = rememberTimePickerState(start / 60, start % 60, false)
    val en = rememberTimePickerState(end / 60, end % 60, false)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Shop hours (location)") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Staff location is saved only between these times.", fontSize = 13.sp)
                Text("Start", fontWeight = FontWeight.Bold)
                TimeInput(state = st)
                Text("End", fontWeight = FontWeight.Bold)
                TimeInput(state = en)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(st.hour * 60 + st.minute, en.hour * 60 + en.minute) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
