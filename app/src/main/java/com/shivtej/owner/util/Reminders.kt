package com.shivtej.owner.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.shivtej.owner.App
import com.shivtej.owner.MainActivity
import com.shivtej.owner.data.TaskE
import java.util.concurrent.TimeUnit

/**
 * Task reminders. Each open task with a due time gets one WorkManager job that shows a notification at that time.
 * Work survives app restarts and phone reboots. Nothing is shown without the notification permission.
 */
object Reminders {
    const val CHANNEL_ID = "tasks_reminders"
    private fun workName(taskId: String) = "task-reminder-$taskId"

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Tasks and reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Reminders for tasks you set in the app"
            }
        )
    }

    /** True when the app may show notifications right now (Android 13+ also needs the runtime permission). */
    fun canNotify(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    }

    /** Schedules the reminder for an open task with a future due time. Returns false when nothing was scheduled. */
    fun schedule(ctx: Context, t: TaskE): Boolean {
        cancel(ctx, t.id)
        if (t.done || t.dueAt <= 0L) return false
        val delay = t.dueAt - System.currentTimeMillis()
        if (delay <= 0L) return false
        val req = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("taskId" to t.id))
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(workName(t.id), ExistingWorkPolicy.REPLACE, req)
        return true
    }

    fun cancel(ctx: Context, taskId: String) {
        WorkManager.getInstance(ctx).cancelUniqueWork(workName(taskId))
    }
}

/** Shows the notification for one task when its time comes. Silent if the task was finished or deleted meanwhile. */
class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("taskId") ?: return Result.success()
        val app = applicationContext as? App ?: return Result.success()
        val t = app.repo.taskById(id) ?: return Result.success()
        if (t.done) return Result.success()
        if (!Reminders.canNotify(applicationContext)) return Result.success()   // permission off: nothing to show
        Reminders.ensureChannel(applicationContext)
        val open = Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(applicationContext, t.notifyId, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(applicationContext, Reminders.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Reminder: " + t.title)
            .setContentText(t.details.ifBlank { "This task is due now." })
            .setStyle(NotificationCompat.BigTextStyle().bigText(t.details.ifBlank { "This task is due now." }))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try {
            NotificationManagerCompat.from(applicationContext).notify(t.notifyId, n)
        } catch (e: SecurityException) {
            // permission was revoked just now; nothing else to do
        }
        return Result.success()
    }
}
