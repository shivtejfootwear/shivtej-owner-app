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
    private var lastCheck = 0L

    private val callback = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) {
            r.lastLocation?.let { send(it) }
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
            val now = System.currentTimeMillis()
            if (store.inHours()) {
                when {
                    !hasPermission(this) -> { stopUpdates(); report("no_permission") }
                    !gpsOn(this) -> { stopUpdates(); report("gps_off") }
                    else -> startUpdates()
                }
            } else {
                stopUpdates()
                // outside the hours: only check now and then that the access is still open (and get new hours)
                if (now - lastCheck > 30 * 60_000L) {
                    lastCheck = now
                    val t = store.token ?: return
                    try { store.saveHours(Api.check(t, store.deviceId).optJSONObject("hours")) }
                    catch (e: NotAllowed) { closed(); return }
                    catch (_: Exception) { }
                }
            }
            delay(60_000L)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startUpdates() {
        if (updating) return
        val ms = store.intervalMin * 60_000L
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, ms).setMinUpdateIntervalMillis(ms / 2).build()
        try {
            fused.requestLocationUpdates(req, callback, Looper.getMainLooper())
            fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).addOnSuccessListener { it?.let(::send) }
            updating = true
        } catch (_: Exception) { }
    }

    private fun stopUpdates() {
        if (!updating) return
        runCatching { fused.removeLocationUpdates(callback) }
        updating = false
    }

    private fun send(l: Location) {
        val now = System.currentTimeMillis()
        if (!store.inHours() || now - lastSent < store.intervalMin * 60_000L - 45_000L) return
        lastSent = now
        val t = store.token ?: return
        scope.launch {
            try {
                val r = Api.location(t, store.deviceId, l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy else null, battery(), "ok")
                store.saveHours(r.optJSONObject("hours"))
            } catch (e: NotAllowed) { closed() }
            catch (_: Exception) { lastSent = 0L } // no internet: try again with the next point
        }
    }

    /** Tells the owner that the GPS is off or the permission was taken away (once per interval). */
    private suspend fun report(status: String) {
        val now = System.currentTimeMillis()
        if (now - lastStatusSent < store.intervalMin * 60_000L) return
        lastStatusSent = now
        val t = store.token ?: return
        try { Api.location(t, store.deviceId, null, null, null, battery(), status) }
        catch (e: NotAllowed) { closed() }
        catch (_: Exception) { lastStatusSent = 0L }
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
