package com.shivtej.owner.util

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.shivtej.owner.MainActivity
import com.shivtej.owner.net.Api
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phone notification when a manager sends a bill or a customer return from the Managers app.
 * Checked by the background sync (about every 15 minutes). The first check only remembers what is already
 * waiting, so old bills do not notify again.
 */
object StaffBillAlert {
    private const val PREFS = "staff_bill_alert"
    private const val NOTE_ID = 9100

    fun check(ctx: Context, code: String) {
        if (code.isBlank()) return
        val a = Api.rpc("owner_staff_bills_list", JSONObject().put("p_code", code)) as? JSONArray ?: return
        val list = (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        val now = list.map { it.optString("id") }.toSet()
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("primed", false)) {
            prefs.edit().putStringSet("seen", now).putBoolean("primed", true).apply()
            return
        }
        val seen = prefs.getStringSet("seen", null) ?: emptySet()
        val fresh = list.filter { it.optString("id") !in seen }
        if (fresh.isNotEmpty()) notify(ctx, fresh)
        prefs.edit().putStringSet("seen", now).apply()
    }

    private fun pieces(o: JSONObject): Int {
        val items = o.optJSONArray("items") ?: return 0
        return (0 until items.length()).sumOf { items.optJSONObject(it)?.optInt("qty", 1) ?: 0 }
    }

    private fun notify(ctx: Context, fresh: List<JSONObject>) {
        if (!Reminders.canNotify(ctx)) return
        Reminders.ensureChannel(ctx)
        val first = fresh.first()
        val isReturn = fresh.any { it.optString("kind") == "return" }
        val title = if (isReturn) "मैनेजर की वापसी" else "मैनेजर का नया बिल"
        val text = if (fresh.size == 1) "${first.optString("staff_name", "Manager")} · ${pieces(first)} पीस"
                   else "${fresh.size} नए बिल/वापसी · Staff Bills में देखें"
        val open = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(ctx, NOTE_ID, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, Reminders.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(NOTE_ID + (System.currentTimeMillis() % 1000).toInt(), n) }
    }
}
