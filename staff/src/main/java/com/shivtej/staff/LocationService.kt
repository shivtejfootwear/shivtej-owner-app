package com.shivtej.staff

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Sends the staff member's location to the shop owner, ONLY inside the shop hours, every few minutes
 * (the owner sets the hours; the server also refuses points outside them). A notification shows all the time
 * while this runs (Android rule), so the staff always knows. Outside the hours the GPS is not used at all.
 * Stops by itself when the owner closes the access.
 */
class LocationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var store: Store
    private lateinit var fused: FusedLocationProviderClient
    private var updating = false
    private var lastSent = 0L
    private var lastStatusSent = 0L
    @Volatile private var lastCheck = 0L
    private var activeSec = 0

    private val callback = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) {
            r.lastLocation?.let { send(it) }
            scope.launch { checkConf(2 * 60_000L) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        fused = LocationServices.getFusedLocationProviderClient(this)
        if (!startInForeground()) { stopSelf(); return }
        scope.launch { loop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopUpdates()
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Shop hours location", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_location)
            .setContentTitle("Shivtej Staff")
            .setContentText("दुकान के समय (${store.hoursText()}) लोकेशन दुकान मालिक को जाती है")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        return try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(NOTE_ID, n)
            true
        } catch (e: Exception) {
            false // no location permission yet: the app starts the service again after the permission is given
        }
    }

    private suspend fun loop() {
        while (scope.isActive) {
            if (store.token == null) { stopSelf(); return }
            if (store.inHours()) {
                when {
                    !hasPermission(this) -> { stopUpdates(); report("no_permission") }
                    !gpsOn(this) -> { stopUpdates(); report("gps_off") }
                    else -> startUpdates()
                }
                checkConf(2 * 60_000L)          // the owner may switch Live on: learn it within about 2 minutes
            } else {
                stopUpdates()
                checkConf(30 * 60_000L)         // outside the hours: only check now and then (access, new hours)
            }
            delay(60_000L)
        }
    }

    /** Asks the server for the access, the hours and this staff member's interval, at most once per [every] ms. */
    private suspend fun checkConf(every: Long) {
        val now = System.currentTimeMillis()
        if (now - lastCheck < every) return
        lastCheck = now
        val t = store.token ?: return
        try {
            store.saveHours(Api.check(t, store.deviceId).optJSONObject("hours"))
            if (updating && activeSec != store.intervalSec) launchRestart()
        } catch (e: NotAllowed) { closed() }
        catch (_: Exception) { }
    }

    /**
     * Up to 5 minutes: GPS updates at the chosen interval (Live = every 15 seconds).
     * Longer (30 min .. 2 hours): cheap network location every 2 minutes, which keeps the app awake enough to notice
     * when the owner switches Live on; a point is sent only when the chosen time has passed.
     */
    @SuppressLint("MissingPermission")
    private fun startUpdates() {
        if (updating) return
        val sec = store.intervalSec
        val req = if (sec <= 300) {
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, sec * 1000L).setMinUpdateIntervalMillis(sec * 500L).build()
        } else {
            LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 120_000L).setMinUpdateIntervalMillis(60_000L).build()
        }
        try {
            fused.requestLocationUpdates(req, callback, Looper.getMainLooper())
            fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).addOnSuccessListener { it?.let(::send) }
            updating = true
            activeSec = sec
        } catch (_: Exception) { }
    }

    private fun stopUpdates() {
        if (!updating) return
        runCatching { fused.removeLocationUpdates(callback) }
        updating = false
    }

    private fun send(l: Location) {
        val now = System.currentTimeMillis()
        val ms = store.intervalSec * 1000L
        if (!store.inHours() || now - lastSent < ms - minOf(45_000L, ms / 4)) return
        lastSent = now
        val t = store.token ?: return
        scope.launch {
            try {
                val r = Api.location(t, store.deviceId, l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy else null, battery(), "ok")
                store.saveHours(r.optJSONObject("hours"))
                if (activeSec != store.intervalSec) launchRestart()
            } catch (e: NotAllowed) { closed() }
            catch (_: Exception) { lastSent = 0L } // no internet: try again with the next point
        }
    }

    /** Tells the owner that the GPS is off or the permission was taken away (once per interval). */
    private suspend fun report(status: String) {
        val now = System.currentTimeMillis()
        if (now - lastStatusSent < maxOf(store.intervalSec, 60) * 1000L) return
        lastStatusSent = now
        val t = store.token ?: return
        try { Api.location(t, store.deviceId, null, null, null, battery(), status) }
        catch (e: NotAllowed) { closed() }
        catch (_: Exception) { lastStatusSent = 0L }
    }

    /** The owner changed the interval: start the location updates again with the new one (on the main thread). */
    private fun launchRestart() {
        android.os.Handler(Looper.getMainLooper()).post { stopUpdates(); if (store.inHours() && hasPermission(this) && gpsOn(this)) startUpdates() }
    }

    private fun closed() {
        store.logout(closedByOwner = true)
        stopUpdates()
        stopSelf()
    }

    private fun battery(): Int =
        runCatching { getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }
            .getOrDefault(-1).coerceIn(0, 100)

    companion object {
        private const val CHANNEL = "shop_hours_location"
        private const val NOTE_ID = 7

        fun hasPermission(ctx: Context): Boolean =
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        /** Precise (exact) location allowed - the catalog opens only with this, not with "approximate". */
        fun hasPrecise(ctx: Context): Boolean =
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        fun gpsOn(ctx: Context): Boolean =
            runCatching { LocationManagerCompat.isLocationEnabled(ctx.getSystemService(LocationManager::class.java)) }.getOrDefault(false)

        fun start(ctx: Context) {
            if (!hasPermission(ctx) || Store(ctx).token == null) return
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, LocationService::class.java)) }
        }
    }
}
