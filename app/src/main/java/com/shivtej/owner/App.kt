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
    lateinit var prefs: Prefs
    lateinit var db: AppDb
    lateinit var repo: Repo
    lateinit var sync: SyncManager
    lateinit var net: Net
    lateinit var cart: com.shivtej.owner.data.CartStore
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        db = AppDb.build(this)
        repo = Repo(db, prefs)
        sync = SyncManager(repo, prefs, scope, this)
        net = Net(this)
        cart = com.shivtej.owner.data.CartStore(prefs)
        repo.onChanged = { sync.kick() }
        SyncWorker.schedule(this)
        scope.launch { net.online.collect { if (it) sync.kick() } }
    }
}
