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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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

// ---- premium palette (used across the Managers app) ----
val HeaderTop = Color(0xFF17315F)   // deep premium navy (header gradient top)
val HeaderBot = Color(0xFF0E1E3C)   // header gradient bottom
val Gold = Color(0xFFD4AF37)        // subtle premium accent line
val PanelBg = Color(0xFFF3F5FA)     // soft app background
val Ink = Color(0xFF0F172A)         // strong text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No screenshots or screen recording of the catalog (the screen shows black in them)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = StaffBlue, secondary = HeaderTop, tertiary = Gold,
                    background = PanelBg, surface = Color.White, onSurface = Ink
                )
            ) {
                Surface(Modifier.fillMaxSize(), color = PanelBg) { StaffApp() }
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
        else -> {
            var screen by remember { mutableStateOf("catalog") }
            val logout = { store.logout(closedByOwner = true); token = null }
            when (screen) {
                "makebill" -> MakeBillScreen(store, onBack = { screen = "catalog" }, onClosed = logout)
                else -> CatalogScreen(store, name, onMakeBill = { screen = "makebill" }, onClosed = logout)
            }
        }
    }
}

@Composable
private fun Logo() {
    Image(painterResource(R.drawable.staff_logo), null, Modifier.size(84.dp))
}

/**
 * A premium gradient header used on every screen of the Managers app.
 * Deep navy gradient, a thin gold accent line, white bold title + optional subtitle, and action icons on the right.
 */
@Composable
fun PremiumHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Column(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(HeaderTop, HeaderBot)))
            .statusBarsPadding()
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back", tint = Color.White) }
            else Spacer(Modifier.width(14.dp))
            Image(
                painterResource(R.drawable.staff_logo), null,
                Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).background(Color.White).padding(3.dp)
            )
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(title, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp)
                if (!subtitle.isNullOrBlank())
                    Text(subtitle, color = Color(0xFFBFD0EA), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            actions()
        }
        // gold accent line
        Spacer(Modifier.fillMaxWidth().height(2.dp).background(Gold))
    }
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
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(HeaderTop, HeaderBot)))
            .verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(Modifier.height(40.dp))
        Box(Modifier.size(104.dp).clip(RoundedCornerShape(24.dp)).background(Color.White).padding(10.dp),
            contentAlignment = Alignment.Center) { Logo() }
        Text("Shivtej Managers", fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, color = Color.White)
        Text("दुकान का कैटलॉग और बिल बनाने के लिए", fontSize = 13.sp, color = Color(0xFFBFD0EA))
        Spacer(Modifier.height(8.dp))
        Card(
            Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(8.dp)
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Login", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Ink)
                if (closed) Text("इस फ़ोन का access बंद है। दुकान मालिक से नया कोड लें।", color = BrandRed,
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Text("अपना मोबाइल नंबर और दुकान मालिक से मिला 6 अंक का कोड डालें।",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        OutlinedTextField(mobile, { mobile = it.filter(Char::isDigit).take(10) }, label = { Text("मोबाइल नंबर") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text("6 अंक का कोड") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
        Button(
            enabled = !busy && mobile.length == 10 && code.length == 6,
            colors = ButtonDefaults.buttonColors(containerColor = StaffBlue),
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
                ) { Text(if (busy) "रुकिए..." else "Login", fontWeight = FontWeight.Bold) }
            }
        }
        Spacer(Modifier.height(20.dp))
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
