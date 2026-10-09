@file:OptIn(ExperimentalMaterial3Api::class)

package com.shivtej.owner.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.shivtej.owner.App
import com.shivtej.owner.data.Prefs
import com.shivtej.owner.util.Images
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Tells the screens that the theme or the wallpaper was changed (the colour scheme is rebuilt). */
object ThemeBus {
    val version = MutableStateFlow(0)
    fun changed() { version.value = version.value + 1 }
}

/** The colour scheme chosen in Settings. Text colours are dark on light surfaces and light on dark surfaces. */
fun buildScheme(prefs: Prefs, systemDark: Boolean): ColorScheme {
    val dark = isDarkNow(prefs, systemDark)
    val accent = if (prefs.accentArgb != 0) Color(prefs.accentArgb) else null
    return if (dark) darkColorScheme(
        primary = accent ?: Color(0xFFEF9A9A), onPrimary = Color(0xFF2B0000),
        secondary = Color(0xFFFFCDD2),
        background = Color(0xFF121212), onBackground = Color(0xFFF5F5F5),
        surface = Color(0xFF1E1E1E), onSurface = Color(0xFFF5F5F5),
        surfaceVariant = Color(0xFF2A2A2A), onSurfaceVariant = Color(0xFFD9D9D9),
        outline = Color(0xFF8A8A8A)
    ) else lightColorScheme(
        primary = accent ?: Color(0xFFC62828), onPrimary = Color.White,
        secondary = Color(0xFF8E0000),
        background = Color(0xFFFFFFFF), onBackground = Color(0xFF111111),
        surface = Color(0xFFFFFFFF), onSurface = Color(0xFF111111),
        surfaceVariant = Color(0xFFF2F2F2), onSurfaceVariant = Color(0xFF333333),
        outline = Color(0xFF6B6B6B)
    )
}

/** On a dark background, brand colours are lightened so text and icons stay easy to read. Light themes keep the colour. */
@Composable
fun readableTone(c: Color): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) lerp(c, Color.White, 0.35f) else c

fun isDarkNow(prefs: Prefs, systemDark: Boolean): Boolean = when (prefs.themeMode) {
    "dark" -> true
    "light", "redwhite" -> false
    else -> systemDark
}

class BuiltinWall(val id: String, val label: String, val top: Color, val bottom: Color)

val BUILTIN_WALLS = listOf(
    BuiltinWall("redwhite", "Red & White", Color(0xFFC62828), Color(0xFFFFFFFF)),
    BuiltinWall("charcoal", "Charcoal", Color(0xFF1F2024), Color(0xFF3A3D45)),
    BuiltinWall("sunset", "Sunset", Color(0xFFE30613), Color(0xFFFF8A65)),
    BuiltinWall("rose", "Rose", Color(0xFFF8BBD0), Color(0xFFFFFFFF)),
    BuiltinWall("slate", "Slate", Color(0xFF263238), Color(0xFF607D8B)),
    BuiltinWall("sand", "Sand", Color(0xFFFFF8E1), Color(0xFFD7CCC8))
)

/** Draws the wallpaper (built-in gradient or the gallery picture) with the readability cover on top. */
@Composable
fun WallpaperFill(kind: String, builtinId: String, path: String, overlayPct: Int, dark: Boolean, modifier: Modifier = Modifier) {
    Box(modifier) {
        when {
            kind == "builtin" -> {
                val w = BUILTIN_WALLS.firstOrNull { it.id == builtinId } ?: BUILTIN_WALLS[2]
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(w.top, w.bottom))))
            }
            kind == "gallery" && path.isNotBlank() && File(path).exists() ->
                AsyncImage(model = File(path), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        }
        val alpha = overlayPct.coerceIn(0, 90) / 100f
        Box(Modifier.matchParentSize().background((if (dark) Color.Black else Color.White).copy(alpha = alpha)))
    }
}

/** Put inside the main screen area. Shows the wallpaper only where the owner chose (dashboard or every main screen). */
@Composable
fun WallpaperLayer(app: App, isDashboard: Boolean) {
    val version by ThemeBus.version.collectAsState()
    val p = app.prefs
    val show = p.wallKind != "none" && (p.wallScope == "app" || isDashboard)
    if (!show) return
    val dark = isDarkNow(p, androidx.compose.foundation.isSystemInDarkTheme())
    key(version) {
        WallpaperFill(p.wallKind, p.wallBuiltin, p.wallPath, p.wallOverlay, dark, Modifier.fillMaxSize())
    }
}

private val ACCENTS = listOf(0, 0xFFC62828.toInt(), 0xFF1565C0.toInt(), 0xFF2E7D32.toInt(), 0xFFEF6C00.toInt(), 0xFF6A1B9A.toInt())

/** Settings > Theme & wallpaper. Theme and accent apply at once; a wallpaper is previewed first and applied with "Apply". */
@Composable
fun ThemeSettingsScreen(app: App) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val p = app.prefs
    val version by ThemeBus.version.collectAsState()
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val dark = isDarkNow(p, systemDark)

    var mode by remember(version) { mutableStateOf(p.themeMode) }
    var accent by remember(version) { mutableIntStateOf(p.accentArgb) }
    var pendKind by remember(version) { mutableStateOf(p.wallKind) }
    var pendBuiltin by remember(version) { mutableStateOf(p.wallBuiltin) }
    var pendPath by remember(version) { mutableStateOf(p.wallPath) }
    var pendScope by remember(version) { mutableStateOf(p.wallScope) }
    var pendOverlay by remember(version) { mutableIntStateOf(p.wallOverlay) }
    var busy by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                val path = withContext(Dispatchers.IO) { Images.importUri(ctx, uri) }
                busy = false
                if (path == null) toast(ctx, "This picture can not be read. Choose a JPG or PNG.")
                else { pendPath = path; pendKind = "gallery" }
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Theme", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("system" to "System", "light" to "Light", "dark" to "Dark", "redwhite" to "Red & White").forEach { (id, label) ->
                        FilterChip(selected = mode == id, onClick = {
                            mode = id; p.themeMode = id; ThemeBus.changed()
                        }, label = { Text(label) })
                    }
                }
                Text("Accent colour", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    ACCENTS.forEach { c ->
                        val shown = if (c == 0) Color(0xFFC62828) else Color(c)
                        Box(Modifier.size(36.dp).clip(RoundedCornerShape(18.dp)).background(shown)
                            .clickable { accent = c; p.accentArgb = c; ThemeBus.changed() },
                            contentAlignment = Alignment.Center) {
                            if (accent == c) Text("✓", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text(if (accent == 0) "Default red" else "Custom", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Wallpaper", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("Choose a built-in wallpaper or your own picture. You see a preview first.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BUILTIN_WALLS.forEach { w ->
                        Column(Modifier.width(78.dp).clickable { pendKind = "builtin"; pendBuiltin = w.id }, horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(64.dp).clip(RoundedCornerShape(10.dp))
                                .background(Brush.verticalGradient(listOf(w.top, w.bottom))))
                            Text(w.label, fontSize = 11.sp, fontWeight = if (pendKind == "builtin" && pendBuiltin == w.id) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
                OutlinedButton(onClick = { picker.launch(arrayOf("image/*")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (busy) "Reading picture..." else "Add wallpaper from gallery")
                }

                Text("Preview", fontWeight = FontWeight.SemiBold)
                Box(Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(12.dp))) {
                    WallpaperFill(pendKind, pendBuiltin, pendPath, pendOverlay, dark, Modifier.fillMaxSize())
                    Column(Modifier.align(Alignment.Center).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                            Column(Modifier.padding(12.dp)) {
                                Text("Today's sales", color = Color(0xFF111111), fontWeight = FontWeight.Bold)
                                Text("Rs ----", color = Color(0xFF111111), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                            }
                        }
                    }
                }

                Text("Apply wallpaper to", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = pendScope == "dashboard", onClick = { pendScope = "dashboard" }, label = { Text("Dashboard only") })
                    FilterChip(selected = pendScope == "app", onClick = { pendScope = "app" }, label = { Text("All main screens") })
                }
                Text("Cover for readability: ${pendOverlay}%", fontWeight = FontWeight.SemiBold)
                Slider(value = pendOverlay.toFloat(), onValueChange = { pendOverlay = it.toInt() }, valueRange = 0f..90f, steps = 17)

                Button(onClick = {
                    if (pendKind == "none") { p.wallKind = "none" } else {
                        p.wallKind = pendKind
                        p.wallBuiltin = pendBuiltin
                        if (pendKind == "gallery") p.wallPath = pendPath
                    }
                    p.wallScope = pendScope
                    p.wallOverlay = pendOverlay
                    ThemeBus.changed()
                    toast(ctx, "Wallpaper applied")
                }, modifier = Modifier.fillMaxWidth()) { Text("Apply wallpaper") }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        pendKind = "none"; p.wallKind = "none"; ThemeBus.changed()
                        toast(ctx, "Wallpaper removed")
                    }) { Text("Remove wallpaper") }
                    TextButton(onClick = {
                        mode = "system"; accent = 0; pendKind = "none"; pendScope = "dashboard"; pendOverlay = 60
                        p.themeMode = "system"; p.accentArgb = 0; p.wallKind = "none"; p.wallScope = "dashboard"; p.wallOverlay = 60
                        ThemeBus.changed()
                        toast(ctx, "Default theme restored")
                    }) { Text("Restore default theme") }
                }
            }
        }
        Text("Theme and wallpaper are saved on this phone and stay after a restart. They do not change any shop data.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
