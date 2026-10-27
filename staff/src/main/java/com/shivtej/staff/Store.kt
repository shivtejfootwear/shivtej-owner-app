package com.shivtej.staff

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.UUID

/** What the staff app keeps on the phone: this phone's id, the login key, the staff name and the shop hours. */
class Store(ctx: Context) {
    private val app = ctx.applicationContext
    private val sp = app.getSharedPreferences("staff", Context.MODE_PRIVATE)

    /** A random id made once for this phone (the login works only on the phone it was made on). */
    val deviceId: String
        get() {
            sp.getString("device_id", null)?.let { return it }
            val d = UUID.randomUUID().toString()
            sp.edit().putString("device_id", d).apply()
            return d
        }

    var token: String?
        get() = sp.getString("token", null)
        set(v) = sp.edit().putString("token", v).apply()
    var name: String
        get() = sp.getString("name", "") ?: ""
        set(v) = sp.edit().putString("name", v).apply()
    var consent: Boolean
        get() = sp.getBoolean("consent", false)
        set(v) = sp.edit().putBoolean("consent", v).apply()
    /** Shown on the login screen once after the owner closed the access. */
    var closedNote: Boolean
        get() = sp.getBoolean("closed_note", false)
        set(v) = sp.edit().putBoolean("closed_note", v).apply()

    var startMin: Int
        get() = sp.getInt("start_min", 540)
        private set(v) = sp.edit().putInt("start_min", v).apply()
    var endMin: Int
        get() = sp.getInt("end_min", 1320)
        private set(v) = sp.edit().putInt("end_min", v).apply()
    /** How often the location goes to the owner, in seconds (the owner chooses: Live 15 s ... 2 hours). */
    var intervalSec: Int
        get() = sp.getInt("interval_sec", 600).coerceIn(10, 7200)
        private set(v) = sp.edit().putInt("interval_sec", v).apply()

    fun saveHours(o: JSONObject?) {
        if (o == null) return
        startMin = o.optInt("start_min", startMin)
        endMin = o.optInt("end_min", endMin)
        intervalSec = if (o.has("interval_sec")) o.optInt("interval_sec", intervalSec) else o.optInt("interval_min", intervalSec / 60) * 60
    }

    fun intervalText(): String = when {
        intervalSec < 60 -> "$intervalSec सेकंड"
        intervalSec < 3600 -> "${intervalSec / 60} मिनट"
        else -> "${intervalSec / 3600} घंटे"
    }

    /** True inside the shop hours (phone time). */
    fun inHours(c: Calendar = Calendar.getInstance()): Boolean {
        val m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        return if (startMin <= endMin) m in startMin..endMin else m >= startMin || m <= endMin
    }

    fun hoursText(): String = "${hhmm(startMin)} – ${hhmm(endMin)}"

    /** Logged out (blocked by the owner, or another phone logged in): the login key and the photo cache are removed. */
    fun logout(closedByOwner: Boolean) {
        sp.edit().remove("token").remove("name").apply()
        if (closedByOwner) closedNote = true
        runCatching { File(app.cacheDir, "image_cache").deleteRecursively() }
        Offline.clear(app) // the saved catalog and photos are deleted too
    }

    companion object {
        fun hhmm(m: Int): String {
            val h = m / 60; val mm = m % 60
            val h12 = if (h % 12 == 0) 12 else h % 12
            return "%d:%02d %s".format(h12, mm, if (h < 12) "AM" else "PM")
        }
    }
}
