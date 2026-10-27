package com.shivtej.owner

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.shivtej.owner.data.AppDb
import com.shivtej.owner.data.Prefs
import com.shivtej.owner.data.Repo
import com.shivtej.owner.sync.SyncManager
import com.shivtej.owner.sync.SyncWorker
import com.shivtej.owner.util.Reminders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class Net(app: Application) {
    private val cm = app.getSystemService(ConnectivityManager::class.java)
    val online = MutableStateFlow(check())

    private fun check(): Boolean {
        val n = cm.activeNetwork ?: return false
        val c = cm.getNetworkCapabilities(n) ?: return false
        return c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    init {
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { online.value = true }
            override fun onLost(network: Network) { online.value = check() }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                online.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        })
    }
}

class App : Application() {
    companion object {
        /** Set when the phone's database can not be opened safely; the app then shows this message and touches nothing. */
        @Volatile var dbRefusal: String? = null
    }

    lateinit var prefs: Prefs
    lateinit var db: AppDb
    lateinit var repo: Repo
    lateinit var sync: SyncManager
    lateinit var net: Net
    lateinit var cart: com.shivtej.owner.data.CartStore
    lateinit var printer: com.shivtej.owner.util.PrinterManager
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        com.shivtej.owner.data.BillMeta.rememberLegacy(prefs)   // shop details of bills made before update 2 (taken once)
        // a restore chosen in Settings is applied here, before Room opens the database (nothing else is running yet)
        dbRefusal = com.shivtej.owner.util.BackupRestore.applyPending(this) ?: com.shivtej.owner.data.DbGuard.check(this)
        db = AppDb.build(this)
        repo = Repo(db, prefs)
        sync = SyncManager(repo, prefs, scope, this)
        net = Net(this)
        cart = com.shivtej.owner.data.CartStore(prefs)
        printer = com.shivtej.owner.util.PrinterManager(this, prefs)
        repo.onChanged = { sync.kick() }
        if (dbRefusal == null) {
            SyncWorker.schedule(this)
            // one-time repair of old stock: scanned barcodes count as pieces when the qty was never typed
            if (!prefs.qtyFromBarcodesFixed) scope.launch {
                runCatching { repo.fixQtyFromBarcodes() }
                prefs.qtyFromBarcodesFixed = true
            }
            scope.launch { net.online.collect { if (it) sync.kick() } }
            // task reminders: make sure every open task with a future time still has its reminder (also after a restore)
            Reminders.ensureChannel(this)
            scope.launch {
                repo.allTasks().filter { !it.done && it.dueAt > 0L }
                    .forEach { Reminders.schedule(this@App, it) }
            }
        }
    }
}
