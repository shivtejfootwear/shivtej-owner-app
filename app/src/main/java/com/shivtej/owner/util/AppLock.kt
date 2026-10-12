package com.shivtej.owner.util

import androidx.compose.runtime.mutableStateOf
import com.shivtej.owner.data.Prefs

/**
 * The app's own security lock (the PIN set in Settings). It is separate from the owner code.
 *  - the PIN is asked when the app starts, after the app has been out of use for one day, and when the owner taps "Lock app"
 *  - "out of use" means the app was in the background (closed or switched away) for a full day
 *  - the owner code can reset a forgotten PIN (see PinScreen in MainActivity.kt)
 */
object AppLock {
    /** One day, in milliseconds. */
    const val AUTO_LOCK_MS = 24L * 60 * 60 * 1000

    /** True while the PIN screen must be shown. Read by the app's root screen. */
    val locked = mutableStateOf(false)

    private var started = false

    /** Called once per app start (not again on screen rotation). Locks when a PIN is set and the app was away for a day. */
    fun checkAtStart(prefs: Prefs) {
        if (started) return
        started = true
        locked.value = prefs.appPin.isNotBlank() && awayLongEnough(prefs)
    }

    /** The owner taps "Lock app". Does nothing when no PIN is set. */
    fun lockNow(prefs: Prefs) {
        if (prefs.appPin.isNotBlank()) locked.value = true
    }

    /** The app went to the background: remember when. */
    fun onAppStopped(prefs: Prefs) {
        prefs.lastBackgroundAt = System.currentTimeMillis()
    }

    /** The app came back to the front: lock when it was away for a day. */
    fun onAppStarted(prefs: Prefs) {
        if (prefs.appPin.isNotBlank() && awayLongEnough(prefs)) locked.value = true
    }

    /** True when the app has been in the background for at least [AUTO_LOCK_MS] (or never left, which is treated as away). */
    private fun awayLongEnough(prefs: Prefs): Boolean {
        val last = prefs.lastBackgroundAt
        return last <= 0L || System.currentTimeMillis() - last >= AUTO_LOCK_MS
    }
}
