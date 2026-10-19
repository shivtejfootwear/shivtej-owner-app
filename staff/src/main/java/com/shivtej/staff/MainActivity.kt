@file:OptIn(ExperimentalMaterial3Api::class)

package com.shivtej.staff

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val BrandRed = Color(0xFFC62828)
val StaffBlue = Color(0xFF1565C0)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No screenshots or screen recording of the catalog (the screen shows black in them)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = BrandRed, secondary = StaffBlue)) {
                Surface(Modifier.fillMaxSize(), color = Color.White) { StaffApp() }
            }
        }
    }
}

/** Re-runs [block] every time the app comes back to the screen. */
@Composable
private fun OnResume(block: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(block)
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) latest() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
}

@Composable
fun StaffApp() {
    val ctx = LocalContext.current
    val store = remember { Store(ctx) }
    var token by remember { mutableStateOf(store.token) }
    var perm by remember { mutableStateOf(LocationService.hasPrecise(ctx)) }
    var gps by remember { mutableStateOf(LocationService.gpsOn(ctx)) }
    var name by remember { mutableStateOf(store.name) }
    val scope = rememberCoroutineScope()

    OnResume {
        token = store.token
        perm = LocationService.hasPrecise(ctx)
        gps = LocationService.gpsOn(ctx)
        val t = store.token ?: return@OnResume
        LocationService.start(ctx)
        // the owner may have closed the access: check each time the app opens
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) { Api.check(t, store.deviceId) }
                store.saveHours(r.optJSONObject("hours"))
                name = r.optString("name", store.name).also { store.name = it }
            } catch (e: NotAllowed) {
                store.logout(closedByOwner = true); token = null
            } catch (_: Exception) { }
        }
    }

    when {
        // no consent / warning screen: the app opens straight to the mobile-number + code login
        token == null -> LoginScreen(store) { t, n -> store.token = t; store.name = n; name = n; token = t; LocationService.start(ctx) }
        !perm -> PermissionScreen { perm = LocationService.hasPrecise(ctx); LocationService.start(ctx) }
        !gps -> GpsOffScreen()
        else -> CatalogScreen(store, name) { store.logout(closedByOwner = true); token = null }
    }
}

@Composable
private fun Logo() {
    Image(painterResource(R.drawable.staff_logo), null, Modifier.size(84.dp))
}

@Composable
private fun LoginScreen(store: Store, onLoggedIn: (String, String) -> Unit) {
    val scope = rememberCoroutineScope()
    var mobile by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val closed = remember { store.closedNote }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(Modifier.height(24.dp))
        Logo()
        Text("Manager login", fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
        if (closed) Text("इस फ़ोन का access बंद है। दुकान मालिक से नया कोड लें।", color = BrandRed,
            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text("अपना मोबाइल नंबर और दुकान मालिक से मिला 6 अंक का कोड डालें।", textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(mobile, { mobile = it.filter(Char::isDigit).take(10) }, label = { Text("मोबाइल नंबर") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text("6 अंक का कोड") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
        Button(
            enabled = !busy && mobile.length == 10 && code.length == 6,
            onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        val r = withContext(Dispatchers.IO) {
                            Api.login(mobile, code, store.deviceId, "${Build.MANUFACTURER} ${Build.MODEL}".trim())
                        }
                        when (r.optString("error")) {
                            "WRONG_CODE" -> error = "कोड गलत है। 5 बार गलत होने पर कोड बंद हो जाता है।"
                            "NO_CODE" -> error = "इस नंबर के लिए कोई चालू कोड नहीं है। दुकान मालिक से नया कोड लें।"
                            else -> {
                                store.saveHours(r.optJSONObject("hours"))
                                store.closedNote = false
                                onLoggedIn(r.getString("token"), r.optString("name"))
                            }
                        }
                    } catch (e: Exception) {
                        error = "इंटरनेट चेक करें और फिर से कोशिश करें।"
                    } finally { busy = false }
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text(if (busy) "रुकिए..." else "Login") }
    }
}

@Composable
private fun PermissionScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var askedOnce by remember { mutableStateOf(false) }
    // Notification permission is NOT asked on purpose: on Android 13+ the background-location notification is then
    // not shown to the manager at all. The location still works.
    val loc = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        askedOnce = true
        onDone()
    }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)
    ) {
        Icon(Icons.Filled.LocationOn, null, tint = StaffBlue, modifier = Modifier.size(64.dp))
        Text("लोकेशन की अनुमति दें", fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
        Text("Catalog खोलने के लिए सही (Precise) लोकेशन की अनुमति ज़रूरी है। अगले पेज पर \"Precise\" चालू रखें और \"While using the app\" या \"Allow all the time\" चुनें। \"Approximate\" चुनने पर catalog नहीं खुलेगा।",
            textAlign = TextAlign.Center)
        Button(onClick = {
            loc.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("अनुमति दें") }
        if (askedOnce) OutlinedButton(onClick = {
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
        }, modifier = Modifier.fillMaxWidth()) { Text("Settings खोलें (Permissions → Location → Allow)") }
    }
}

@Composable
private fun GpsOffScreen() {
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)
    ) {
        Icon(Icons.Filled.LocationOff, null, tint = BrandRed, modifier = Modifier.size(64.dp))
        Text("फ़ोन की Location बंद है", fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
        Text("Catalog देखने के लिए फ़ोन की Location (GPS) चालू करें।", textAlign = TextAlign.Center)
        Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
            modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Location चालू करें") }
    }
}
