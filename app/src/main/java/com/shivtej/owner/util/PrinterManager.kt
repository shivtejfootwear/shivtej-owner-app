package com.shivtej.owner.util

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.shivtej.owner.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

enum class PrinterStatus { NO_BLUETOOTH, NO_PERMISSION, BT_OFF, NOT_SAVED, DISCONNECTED, CONNECTING, CONNECTED }

data class PrinterState(val status: PrinterStatus, val message: String = "")

data class PrinterDevice(val name: String, val address: String, val bonded: Boolean)

/**
 * Bluetooth (classic SPP / RFCOMM) connection to an 80mm ESC/POS receipt printer such as the ATPOS AT-301.
 * The printer is paired once, saved on the phone, and every bill can then be printed with one tap.
 * Nothing here touches bills, stock or sync: a failed print never changes any data.
 */
@SuppressLint("MissingPermission")
class PrinterManager(private val ctx: Context, private val prefs: Prefs) {
    private val adapter: BluetoothAdapter? = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private var socket: BluetoothSocket? = null
    private val lock = Mutex()

    val state = MutableStateFlow(PrinterState(PrinterStatus.DISCONNECTED))
    val devices = MutableStateFlow<List<PrinterDevice>>(emptyList())
    val scanning = MutableStateFlow(false)

    companion object {
        /** The standard "Serial Port Profile" id that receipt printers listen on. */
        val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        fun neededPermissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)   // Android 11 and older need location only to SCAN for devices
    }

    // ------------------------------------------------------------------ status
    val hasBluetooth: Boolean get() = adapter != null

    fun hasPermission(): Boolean = neededPermissions().all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

    /** Connecting to an already paired printer needs only BLUETOOTH_CONNECT on Android 12+ and nothing extra before. */
    fun canConnect(): Boolean =
        if (Build.VERSION.SDK_INT >= 31) ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        else true

    val savedName: String get() = prefs.printerName
    val savedAddress: String get() = prefs.printerAddress

    fun refreshStatus() {
        val a = adapter
        val s = socket
        val st = when {
            a == null -> PrinterState(PrinterStatus.NO_BLUETOOTH, "This phone has no Bluetooth.")
            !canConnect() -> PrinterState(PrinterStatus.NO_PERMISSION, "Allow Bluetooth to use the printer.")
            !a.isEnabled -> PrinterState(PrinterStatus.BT_OFF, "Bluetooth is off. Turn it on.")
            prefs.printerAddress.isBlank() -> PrinterState(PrinterStatus.NOT_SAVED, "No printer saved yet.")
            s != null && s.isConnected -> PrinterState(PrinterStatus.CONNECTED, "Connected to " + label())
            else -> PrinterState(PrinterStatus.DISCONNECTED, "Saved: " + label() + " (not connected)")
        }
        if (state.value.status != PrinterStatus.CONNECTING) state.value = st
    }

    private fun label(): String = prefs.printerName.ifBlank { prefs.printerAddress }

    // ------------------------------------------------------------------ find printers
    fun refreshBonded() {
        val a = adapter ?: return
        if (!canConnect()) return
        val bonded = try { a.bondedDevices ?: emptySet() } catch (e: SecurityException) { emptySet() }
        val old = devices.value.filter { !it.bonded && it.address !in bonded.map { b -> b.address } }
        devices.value = sortDevices(bonded.map { PrinterDevice(it.name ?: it.address, it.address, true) } + old)
    }

    private fun sortDevices(list: List<PrinterDevice>): List<PrinterDevice> =
        list.distinctBy { it.address }.sortedWith(compareByDescending<PrinterDevice> { it.bonded }.thenBy { it.name.lowercase() })

    private var receiver: BroadcastReceiver? = null

    fun startScan() {
        val a = adapter ?: return
        refreshBonded()
        if (!hasPermission() || !a.isEnabled) return
        stopScan()
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val d = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
                        val found = PrinterDevice(d.name ?: d.address, d.address, d.bondState == BluetoothDevice.BOND_BONDED)
                        devices.value = sortDevices(devices.value.filter { it.address != found.address } + found)
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> scanning.value = false
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> refreshBonded()
                }
            }
        }
        val f = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(ctx, r, f, ContextCompat.RECEIVER_EXPORTED)
        receiver = r
        scanning.value = true
        if (!a.startDiscovery()) scanning.value = false
    }

    fun stopScan() {
        try { adapter?.cancelDiscovery() } catch (e: Exception) { }
        receiver?.let { try { ctx.unregisterReceiver(it) } catch (e: Exception) { } }
        receiver = null
        scanning.value = false
    }

    /** Starts pairing (the phone shows its own PIN question: ATPOS printers normally use 0000 or 1234). */
    fun pair(address: String): Boolean = try { adapter?.getRemoteDevice(address)?.createBond() ?: false } catch (e: Exception) { false }

    // ------------------------------------------------------------------ connect / disconnect
    private fun closeSocket() {
        try { socket?.close() } catch (e: Exception) { }
        socket = null
    }

    suspend fun connect(address: String, name: String = "", remember: Boolean = true): Result<Unit> = withContext(Dispatchers.IO) {
        lock.withLock { connectLocked(address, name, remember) }
    }

    private fun connectLocked(address: String, name: String, remember: Boolean): Result<Unit> {
        val a = adapter ?: return fail(PrinterStatus.NO_BLUETOOTH, "This phone has no Bluetooth.")
        if (!canConnect()) return fail(PrinterStatus.NO_PERMISSION, "Allow Bluetooth to use the printer.")
        if (!a.isEnabled) return fail(PrinterStatus.BT_OFF, "Bluetooth is off. Turn it on.")
        closeSocket()
        val shown = name.ifBlank { address }
        state.value = PrinterState(PrinterStatus.CONNECTING, "Connecting to $shown ...")
        try { a.cancelDiscovery() } catch (e: Exception) { }
        val dev = try { a.getRemoteDevice(address) } catch (e: Exception) { return fail(PrinterStatus.DISCONNECTED, "Bad printer address.") }
        var lastError: Exception? = null
        val attempts = listOf<() -> BluetoothSocket>(
            { dev.createRfcommSocketToServiceRecord(SPP) },
            { dev.createInsecureRfcommSocketToServiceRecord(SPP) }
        )
        for (make in attempts) {
            try {
                val s = make()
                s.connect()
                socket = s
                if (remember) {
                    prefs.printerAddress = address
                    prefs.printerName = name.ifBlank { dev.name ?: address }
                }
                state.value = PrinterState(PrinterStatus.CONNECTED, "Connected to " + (if (remember) label() else shown))
                return Result.success(Unit)
            } catch (e: Exception) {
                lastError = e
                try { socket?.close() } catch (x: Exception) { }
                socket = null
            }
        }
        val why = "Could not connect to $shown. Make sure the printer is ON, close to the phone, paired, and not connected to another phone."
        state.value = PrinterState(PrinterStatus.DISCONNECTED, why)
        return Result.failure(IOException(why + (lastError?.message?.let { " ($it)" } ?: "")))
    }

    private fun fail(status: PrinterStatus, msg: String): Result<Unit> {
        state.value = PrinterState(status, msg)
        return Result.failure(IllegalStateException(msg))
    }

    fun disconnect() {
        closeSocket()
        refreshStatus()
    }

    /** Forget the saved printer (the pairing in the phone's Bluetooth settings is not touched). */
    fun forget() {
        closeSocket()
        prefs.printerAddress = ""
        prefs.printerName = ""
        refreshStatus()
    }

    // ------------------------------------------------------------------ printing
    /**
     * Sends a finished print job. If the saved printer is not connected it is connected first.
     * If the link was dead before a single byte went out, it reconnects once and sends again; a link that breaks
     * in the middle of a bill is reported (never silently re-sent, that could print the bill twice).
     */
    suspend fun print(job: List<ByteArray>): Result<Unit> = withContext(Dispatchers.IO) {
        lock.withLock {
            val addr = prefs.printerAddress
            if (addr.isBlank()) return@withLock Result.failure(IllegalStateException("No printer saved. Open Printer and choose your ATPOS AT-301."))
            var s = socket
            if (s == null || !s.isConnected) {
                val r = connectLocked(addr, prefs.printerName, false)
                if (r.isFailure) return@withLock r
                s = socket
            }
            var result = writeJob(s, job)
            if (result.second == 0 && result.first != null) {
                // nothing was sent: the old link was dead - connect again once
                val r = connectLocked(addr, prefs.printerName, false)
                if (r.isFailure) return@withLock r
                result = writeJob(socket, job)
            }
            val err = result.first
            if (err == null) {
                Result.success(Unit)
            } else {
                closeSocket()
                state.value = PrinterState(PrinterStatus.DISCONNECTED, "Connection to the printer was lost.")
                Result.failure(IOException("Printing stopped: " + (err.message ?: "connection lost") + ". Check the printer and try again."))
            }
        }
    }

    /** Returns (error or null, number of parts that were written). */
    private suspend fun writeJob(s: BluetoothSocket?, job: List<ByteArray>): Pair<Exception?, Int> {
        if (s == null) return IOException("No connection") to 0
        var written = 0
        try {
            val out = s.outputStream
            for (part in job) {
                out.write(part)
                out.flush()
                written++
                delay(if (part.size > 1000) 70L else 12L)
            }
            delay(250)   // let the last band reach the printer before anything else happens
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return e to written
        }
        return null to written
    }

    suspend fun printBitmap(bmp: Bitmap, cut: Boolean = true): Result<Unit> {
        val b = if (bmp.width == EscPos.DOTS_80MM) bmp else {
            val h = (bmp.height * (EscPos.DOTS_80MM.toFloat() / bmp.width)).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bmp, EscPos.DOTS_80MM, h, true)
        }
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return print(EscPos.pictureJob(px, b.width, b.height, cut = cut))
    }

    suspend fun printText(lines: List<String>): Result<Unit> = print(EscPos.textJob(lines))

    /** A short test page: plain text lines (always works) followed by the shop logo as a picture. */
    suspend fun testPrint(logo: Bitmap?): Result<Unit> {
        val lines = listOf(
            "SHIVTEJ FOOTWEAR",
            "Printer test - 80mm",
            "Printer: " + label(),
            "----------------------------------------------",
            "123456789012345678901234567890123456789012345678",
            "Rs. 1234.50  (text mode prints Rs.)",
            "If you can read this line the printer works."
        )
        val r = print(EscPos.textJob(lines, boldFirst = 1, feedLines = 1, cut = false))
        if (r.isFailure || logo == null) return if (r.isSuccess) print(listOf(EscPos.feed(4), EscPos.CUT)) else r
        val scale = 360f / logo.width
        val small = Bitmap.createScaledBitmap(logo, 360, (logo.height * scale).toInt().coerceAtLeast(1), true)
        val canvasBmp = Bitmap.createBitmap(EscPos.DOTS_80MM, small.height + 8, Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(canvasBmp)
        cv.drawColor(android.graphics.Color.WHITE)
        cv.drawBitmap(small, (EscPos.DOTS_80MM - small.width) / 2f, 4f, null)
        return printBitmap(canvasBmp)
    }
}
