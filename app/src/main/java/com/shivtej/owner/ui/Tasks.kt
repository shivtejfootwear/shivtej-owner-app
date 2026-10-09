@file:OptIn(ExperimentalMaterial3Api::class)

package com.shivtej.owner.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shivtej.owner.App
import com.shivtej.owner.data.TaskE
import com.shivtej.owner.util.Reminders
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val TASK_EXAMPLES = listOf(
    "Call supplier for stock", "Collect udhaar from customer", "Order new stock",
    "Pay supplier bill", "Check cash and close shop", "Follow up with customer"
)

private fun dueText(ms: Long): String = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.ENGLISH).format(Date(ms))

/** Open a notification-settings page for this app (used when notifications are switched off). */
private fun openNotificationSettings(ctx: android.content.Context) {
    val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try { ctx.startActivity(i) } catch (e: Exception) { toast(ctx, "Open Settings > Apps > Shivtej Owner > Notifications") }
}

@Composable
fun TasksScreen(app: App) {
    val ctx = LocalContext.current
    val tasks by app.repo.tasks.collectAsStateWithLifecycle(emptyList())
    var editing by remember { mutableStateOf<TaskE?>(null) }
    var adding by remember { mutableStateOf(false) }
    var allowed by remember { mutableStateOf(Reminders.canNotify(ctx)) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        allowed = Reminders.canNotify(ctx)
    }
    val open = tasks.filter { !it.done }
    val done = tasks.filter { it.done }
    val now = System.currentTimeMillis()

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp)) {
            if (!allowed) {
                item {
                    Card(Modifier.fillMaxWidth().padding(top = 8.dp), colors = CardDefaults.cardColors(containerColor = SoftRed)) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Notifications are off", fontWeight = FontWeight.Bold, color = RedOut)
                            Text("Tasks are saved, but reminders will not pop up until you allow notifications for this app.",
                                fontSize = 13.sp)
                            TextButton(onClick = {
                                if (Build.VERSION.SDK_INT >= 33) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                else openNotificationSettings(ctx)
                            }) { Text("Allow notifications") }
                        }
                    }
                }
            }
            item { SectionTitle("Open tasks (${open.size})") }
            if (open.isEmpty()) item { EmptyState("No open tasks. Tap + to add a task or reminder.") }
            items(open, key = { it.id }) { t ->
                TaskRow(app, t, now, onOpen = { editing = t })
                HorizontalDivider()
            }
            if (done.isNotEmpty()) {
                item { SectionTitle("Done (${done.size})") }
                items(done, key = { it.id }) { t ->
                    TaskRow(app, t, now, onOpen = { editing = t })
                    HorizontalDivider()
                }
            }
        }
        FloatingActionButton(
            onClick = { adding = true }, containerColor = BrandRed, contentColor = Color.White,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) { Icon(Icons.Filled.Add, "Add task") }
    }
    if (adding || editing != null) {
        TaskDialog(app, existing = editing) { adding = false; editing = null }
    }
}

@Composable
private fun TaskRow(app: App, t: TaskE, now: Long, onOpen: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val overdue = !t.done && t.dueAt > 0L && t.dueAt < now
    Row(Modifier.fillMaxWidth().clickable { onOpen() }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = t.done, onCheckedChange = { checked ->
            scope.launch {
                val updated = app.repo.setTaskDone(t.id, checked)
                if (updated != null) {
                    if (checked) Reminders.cancel(ctx, t.id) else Reminders.schedule(ctx, updated)
                }
            }
        })
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(t.title, fontWeight = FontWeight.SemiBold,
                textDecoration = if (t.done) TextDecoration.LineThrough else null)
            if (t.details.isNotBlank()) Text(t.details, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            if (t.dueAt > 0L) {
                Text(
                    (if (overdue) "Overdue · " else if (t.done) "Was due " else "Due ") + dueText(t.dueAt),
                    fontSize = 12.sp, color = if (overdue) RedOut else MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (!t.done) {
                Text("No due date (no reminder)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Create or edit a task. A reminder is scheduled only when a future date and time are saved. */
@Composable
fun TaskDialog(app: App, existing: TaskE?, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var details by remember { mutableStateOf(existing?.details ?: "") }
    var hasDue by remember { mutableStateOf(existing != null && existing.dueAt > 0L) }
    var dueMs by remember {
        mutableStateOf(
            if (existing != null && existing.dueAt > 0L) existing.dueAt
            else Calendar.getInstance().apply {
                add(Calendar.HOUR_OF_DAY, 1); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
            }.timeInMillis
        )
    }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun pickDate() {
        val c = Calendar.getInstance().apply { timeInMillis = dueMs }
        android.app.DatePickerDialog(ctx, { _, y, mo, d ->
            dueMs = Calendar.getInstance().apply { timeInMillis = dueMs; set(y, mo, d) }.timeInMillis
            hasDue = true
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }
    fun pickTime() {
        val c = Calendar.getInstance().apply { timeInMillis = dueMs }
        android.app.TimePickerDialog(ctx, { _, h, min ->
            dueMs = Calendar.getInstance().apply {
                timeInMillis = dueMs; set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, min); set(Calendar.SECOND, 0)
            }.timeInMillis
            hasDue = true
        }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), false).show()
    }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(if (existing == null) "New task / reminder" else "Edit task") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(title, { title = it.take(120) }, label = { Text("What to do") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TASK_EXAMPLES.forEach { ex -> Chip(ex, title == ex) { title = ex } }
                }
                OutlinedTextField(details, { details = it.take(300) }, label = { Text("Details (optional)") }, modifier = Modifier.fillMaxWidth())
                Text("Due date and time", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickDate() }) { Text(SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).format(Date(dueMs))) }
                    OutlinedButton(onClick = { pickTime() }) { Text(SimpleDateFormat("hh:mm a", Locale.ENGLISH).format(Date(dueMs))) }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("In 1 hour", false) {
                        dueMs = System.currentTimeMillis() + 60 * 60 * 1000L; hasDue = true
                    }
                    Chip("Tomorrow 10:00 AM", false) {
                        dueMs = Calendar.getInstance().apply {
                            add(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 10); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
                        }.timeInMillis
                        hasDue = true
                    }
                    Chip("No due date", !hasDue) { hasDue = false }
                }
                Text(
                    if (hasDue) "Reminder at " + dueText(dueMs) + " (a notification will show if notifications are allowed)."
                    else "No due date: this task will not give a reminder.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                if (existing != null) {
                    TextButton(onClick = {
                        scope.launch {
                            Reminders.cancel(ctx, existing.id)
                            app.repo.deleteTask(existing.id)
                            toast(ctx, "Task deleted")
                            onDone()
                        }
                    }) { Text("Delete this task", color = RedOut) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && title.isNotBlank(), onClick = {
                error = null
                if (hasDue && dueMs <= System.currentTimeMillis() && existing?.dueAt != dueMs) {
                    error = "That time is already past. Choose a later date or time."
                    return@TextButton
                }
                busy = true
                if (hasDue && Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                scope.launch {
                    try {
                        val due = if (hasDue) dueMs else 0L
                        val t = app.repo.saveTask(existing?.id, title, details, due)
                        if (t.dueAt > 0L && !t.done) {
                            if (Reminders.schedule(ctx, t)) toast(ctx, "Task saved. Reminder set.")
                            else { Reminders.cancel(ctx, t.id); toast(ctx, "Task saved") }
                        } else {
                            Reminders.cancel(ctx, t.id)
                            toast(ctx, "Task saved")
                        }
                        onDone()
                    } catch (e: Exception) {
                        error = e.message ?: "Could not save the task"
                    } finally { busy = false }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } }
    )
}
