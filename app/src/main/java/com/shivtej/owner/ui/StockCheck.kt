@file:OptIn(ExperimentalMaterial3Api::class)

package com.shivtej.owner.ui

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.shivtej.owner.App
import com.shivtej.owner.data.Product
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.Executors

/** One barcode of one size of one product. */
private data class Hit(val pid: String, val article: String, val colour: String, val size: String, val cats: List<String>)

private fun catsOfProduct(p: Product): List<String> = p.category.split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }

private fun sizeKey(pid: String, colour: String, size: String) = "$pid|$colour|$size"

/** The saved check: category ("" = all), scanned barcodes with their time, hand counts for pieces without a barcode. */
private class CheckState(var cat: String, val scanned: MutableMap<String, Long>, val manual: MutableMap<String, Int>) {
    fun toJson(): String = JSONObject().put("cat", cat)
        .put("scanned", JSONObject(scanned.mapValues { it.value }))
        .put("manual", JSONObject(manual.mapValues { it.value })).toString()

    companion object {
        fun from(s: String): CheckState = try {
            val o = JSONObject(s)
            val sc = o.optJSONObject("scanned") ?: JSONObject()
            val mn = o.optJSONObject("manual") ?: JSONObject()
            CheckState(o.optString("cat", ""),
                sc.keys().asSequence().associateWith { sc.optLong(it) }.toMutableMap(),
                mn.keys().asSequence().associateWith { mn.optInt(it) }.toMutableMap())
        } catch (e: Exception) { CheckState("", mutableMapOf(), mutableMapOf()) }
    }
}

/** Three different sounds + vibration: OK (one beep), again (two beeps), not found (low buzz). */
private class Feedback(ctx: Context, private val soundOn: () -> Boolean) {
    private val tone = try { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100) } catch (e: Exception) { null }
    private val vib = ctx.getSystemService(Vibrator::class.java)
    private val main = Handler(Looper.getMainLooper())

    private fun buzz(ms: Long) { try { vib?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)) } catch (_: Exception) { } }
    private fun pattern(p: LongArray) { try { vib?.vibrate(VibrationEffect.createWaveform(p, -1)) } catch (_: Exception) { } }

    fun ok() { buzz(60); if (soundOn()) tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 120) }
    fun again() {
        pattern(longArrayOf(0, 80, 120, 80))
        if (soundOn()) {
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 110)
            main.postDelayed({ tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 110) }, 230)
        }
    }
    fun notFound() { buzz(400); if (soundOn()) tone?.startTone(ToneGenerator.TONE_PROP_NACK, 350) }
    fun release() { tone?.release() }
}

/** Live camera that stays open; every code it reads goes to onCode (the screen decides what to do). */
@androidx.annotation.OptIn(markerClass = [ExperimentalGetImage::class])
@Composable
private fun ContinuousScanner(onCode: (String) -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(ctx) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(
                Barcode.FORMAT_QR_CODE, Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E, Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_CODE_39, Barcode.FORMAT_CODE_93, Barcode.FORMAT_ITF, Barcode.FORMAT_CODABAR
            ).build()
        )
    }
    val latest by rememberUpdatedState(onCode)
    val main = remember { Handler(Looper.getMainLooper()) }

    DisposableEffect(Unit) {
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(executor) { proxy ->
                val media = proxy.image
                if (media == null) {
                    proxy.close()
                } else {
                    val img = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                    scanner.process(img)
                        .addOnSuccessListener { list ->
                            list.filter { !it.rawValue.isNullOrBlank() }.forEach { b ->
                                val v = b.rawValue!!.trim()
                                main.post { latest(v) }
                            }
                        }
                        .addOnCompleteListener { proxy.close() }
                }
            }
            provider.unbindAll()
            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(ctx))
        onDispose {
            try { future.get().unbindAll() } catch (_: Exception) { }
            scanner.close()
            executor.shutdown()
        }
    }
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxWidth().height(190.dp))
}

@Composable
fun StockCheckDialog(app: App, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val products by app.repo.products.collectAsStateWithLifecycle(emptyList())
    val active = remember(products) { products.filter { it.active } }

    val state = remember { mutableStateOf(CheckState.from(app.repo.prefs.stockCheckJson)) }
    val st = state.value
    var soundOn by remember { mutableStateOf(app.repo.prefs.stockCheckSound) }
    var tab by remember { mutableIntStateOf(0) }
    var lastText by remember { mutableStateOf("स्कैनर चालू है, बारकोड दिखाइए") }
    var lastRed by remember { mutableStateOf(false) }
    var lastCode by remember { mutableStateOf("") }
    var lastAt by remember { mutableLongStateOf(0L) }
    var tick by remember { mutableIntStateOf(0) }
    var confirmApply by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val feedback = remember { Feedback(ctx) { app.repo.prefs.stockCheckSound } }
    DisposableEffect(Unit) { onDispose { feedback.release() } }

    fun save() { app.repo.prefs.stockCheckJson = st.toJson(); tick++ }

    // every barcode -> its size, and the categories of its product
    val index = remember(active) {
        val m = HashMap<String, Hit>()
        active.forEach { p ->
            p.variants.forEach { v ->
                v.sizes.forEach { s ->
                    (s.barcodes.ifEmpty { listOfNotNull(s.barcode.takeIf { it.isNotBlank() }) }).forEach { c ->
                        m[c] = Hit(p.id, p.article.ifBlank { p.name }, v.colour, s.size, catsOfProduct(p))
                    }
                }
            }
        }
        m
    }
    val allCats = remember(active) { active.flatMap { catsOfProduct(it) }.distinct().sorted() }

    // the pieces of the chosen category (or all): expected barcodes per size
    val inScope = remember(active, st.cat, tick) {
        active.filter { st.cat.isEmpty() || st.cat in catsOfProduct(it) }
    }
    data class SizeRow(val pid: String, val article: String, val colour: String, val size: String,
                       val codes: List<String>, val noBarcode: Int)
    val sizeRows = remember(inScope) {
        inScope.flatMap { p ->
            p.variants.flatMap { v ->
                v.sizes.map { s ->
                    val codes = s.barcodes.ifEmpty { listOfNotNull(s.barcode.takeIf { it.isNotBlank() }) }
                    SizeRow(p.id, p.article.ifBlank { p.name }, v.colour, s.size, codes,
                        (s.qty - codes.size).coerceAtLeast(0))
                }
            }
        }.filter { it.codes.isNotEmpty() || it.noBarcode > 0 }
    }
    val inScopeCodes = remember(sizeRows) { sizeRows.flatMap { it.codes }.toSet() }
    val scannedHere = st.scanned.keys.filter { it in inScopeCodes }
    val missingCount = inScopeCodes.size - scannedHere.size
    val totalPieces = inScopeCodes.size + sizeRows.sumOf { it.noBarcode }
    val handCounted = sizeRows.filter { it.noBarcode > 0 }.sumOf { r ->
        st.manual[sizeKey(r.pid, r.colour, r.size)] ?: 0
    }

    fun onCode(code: String) {
        val now = System.currentTimeMillis()
        if (code == lastCode && now - lastAt < 1200) return      // the same label still in view: one scan
        lastCode = code; lastAt = now
        val hit = index[code]
        when {
            hit == null -> {
                feedback.notFound(); lastRed = true
                lastText = "बारकोड नहीं मिला: $code"
            }
            st.cat.isNotEmpty() && st.cat !in hit.cats -> {
                feedback.notFound(); lastRed = true
                lastText = "यह आइटम इस कैटेगरी का नहीं: ${hit.article} · ${hit.colour} · ${hit.size}"
            }
            st.scanned.containsKey(code) -> {
                feedback.again(); lastRed = true
                lastText = "दो बार स्कैन: ${hit.article} · ${hit.colour} · ${hit.size}"
            }
            else -> {
                st.scanned[code] = now
                feedback.ok(); lastRed = false
                lastText = "स्कैन ✓ ${hit.article} · ${hit.colour} · ${hit.size}"
                save()
            }
        }
        tick++
    }

    val applyList = remember(tick) { st.scanned.keys.toSet() }
    fun shareCheck() {
        val sb = StringBuilder("स्टॉक चेक · ").append(if (st.cat.isEmpty()) "ALL" else st.cat).append("\n")
        sb.append("\nस्कैन हुए (").append(scannedHere.size).append("):\n")
        sizeRows.forEach { r -> r.codes.filter { it in st.scanned }.forEach { c -> sb.append("${r.article} · ${r.colour} · ${r.size} · $c\n") } }
        sb.append("\nनहीं स्कैन हुए ($missingCount):\n")
        sizeRows.forEach { r -> r.codes.filter { it !in st.scanned }.forEach { c -> sb.append("${r.article} · ${r.colour} · ${r.size} · $c\n") } }
        sb.append("\nबिना बारकोड (हाथ से): $handCounted\n")
        val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, sb.toString())
        ctx.startActivity(Intent.createChooser(share, "Share stock check").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("स्टॉक चेक", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { soundOn = !soundOn; app.repo.prefs.stockCheckSound = soundOn }) {
                        Text(if (soundOn) "🔊 आवाज़ चालू" else "🔇 आवाज़ बंद")
                    }
                    TextButton(onClick = onClose) { Text("बंद करें") }
                }

                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = st.cat.isEmpty(), onClick = { st.cat = ""; save() }, label = { Text("ALL") })
                    allCats.take(4).forEach { c ->
                        FilterChip(selected = st.cat == c, onClick = { st.cat = c; save() }, label = { Text(c) })
                    }
                }
                if (allCats.size > 4) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        allCats.drop(4).forEach { c ->
                            FilterChip(selected = st.cat == c, onClick = { st.cat = c; save() }, label = { Text(c) })
                        }
                    }
                }

                // the scanner stays open all the time
                ContinuousScanner { onCode(it) }
                Text(lastText, color = if (lastRed) Color(0xFFC62828) else Color(0xFF2E7D32), fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp, modifier = Modifier.padding(vertical = 4.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("स्कैन: ${scannedHere.size}", fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                    Text("बाकी: $missingCount", fontWeight = FontWeight.Bold, color = Color(0xFFC62828))
                    Text("कुल: ${totalPieces}", fontWeight = FontWeight.Bold)
                }
                Text("हाथ से गिनती (बिना बारकोड): $handCounted", fontSize = 12.sp)

                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("स्कैन हुए (${scannedHere.size})") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("नहीं स्कैन हुए ($missingCount)") })
                }

                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 6.dp)) {
                    if (tab == 0) {
                        val byItem = sizeRows.groupBy { "${it.article} · ${it.colour}" }
                        byItem.forEach { (title, rows) ->
                            item(key = "s_$title") {
                                Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                            }
                            items(rows, key = { "s_${it.pid}|${it.colour}|${it.size}" }) { r ->
                                val got = r.codes.count { it in st.scanned }
                                Text("  साइज़ ${r.size}: स्कैन $got / ${r.codes.size}" +
                                    if (r.noBarcode > 0) "  (हाथ से ${st.manual[sizeKey(r.pid, r.colour, r.size)] ?: 0})" else "",
                                    fontSize = 13.sp)
                            }
                        }
                        if (sizeRows.isEmpty()) item { Text("इस कैटेगरी में कोई आइटम नहीं", fontSize = 13.sp) }
                    } else {
                        val byItem = sizeRows.groupBy { "${it.article} · ${it.colour}" }
                        byItem.forEach { (title, rows) ->
                            val missRows = rows.filter { r -> r.codes.any { it !in st.scanned } || r.noBarcode > 0 }
                            if (missRows.isNotEmpty()) item(key = "m_$title") {
                                Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                            }
                            items(missRows, key = { "m_${it.pid}|${it.colour}|${it.size}" }) { r ->
                                val miss = r.codes.count { it !in st.scanned }
                                Column(Modifier.padding(start = 8.dp, bottom = 4.dp)) {
                                    Text("साइज़ ${r.size}: बाकी $miss" + if (r.noBarcode > 0) " · बिना बारकोड ${r.noBarcode}" else "",
                                        fontSize = 13.sp, color = Color(0xFFC62828))
                                    if (r.noBarcode > 0) {
                                        val k = sizeKey(r.pid, r.colour, r.size)
                                        OutlinedTextField(
                                            value = (st.manual[k]?.toString() ?: ""),
                                            onValueChange = { v ->
                                                val n = v.filter(Char::isDigit).take(4).toIntOrNull()
                                                if (n == null) st.manual.remove(k) else st.manual[k] = n
                                                save()
                                            },
                                            label = { Text("हाथ से गिनती (बिना बारकोड)") },
                                            singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { confirmClear = true }, modifier = Modifier.weight(1f)) { Text("सब साफ़ करें", fontSize = 12.sp) }
                    OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f)) { Text("रोकें (बाद में जारी)", fontSize = 12.sp) }
                    Button(onClick = { confirmApply = true }, modifier = Modifier.weight(1f)) { Text("लागू करें", fontSize = 12.sp) }
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { shareCheck() }, modifier = Modifier.weight(1f)) {
                        Text("सूची शेयर (WhatsApp / Excel)", fontSize = 12.sp)
                    }
                }
            }
        }
    }

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("सब साफ़ करें?") },
        text = { Text("यह चेक पूरा हटा देगा, नया शुरू होगा। स्टॉक में कुछ नहीं बदलेगा।") },
        confirmButton = { TextButton(onClick = {
            st.scanned.clear(); st.manual.clear(); save(); lastText = "नया चेक शुरू हुआ"; lastRed = false; confirmClear = false
        }) { Text("हाँ, साफ़ करें") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("रद्द") } })

    if (confirmApply) AlertDialog(
        onDismissRequest = { confirmApply = false },
        title = { Text("स्टॉक चेक लागू करें?") },
        text = { Text("स्कैन हुए: ${scannedHere.size} पीस, स्टॉक वही रहेगा।\n" +
            "नहीं स्कैन हुए: $missingCount पीस, ये स्टॉक से हट जाएँगे (बिका या गायब मानें)।\n" +
            "हाथ से गिनती: $handCounted।\nसूची एक बार देख लें।") },
        confirmButton = { TextButton(onClick = {
            confirmApply = false
            scope.launch {
                val changed = app.repo.applyStockCheck(inScope, applyList, st.manual.toMap())
                st.scanned.clear(); st.manual.clear(); save()
                toast(ctx, "स्टॉक चेक लागू हुआ: $changed साइज़ बदले")
                onClose()
            }
        }) { Text("हाँ, लागू करें") } },
        dismissButton = { TextButton(onClick = { confirmApply = false }) { Text("रद्द") } })
}

