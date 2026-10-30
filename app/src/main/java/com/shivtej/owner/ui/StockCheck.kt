@file:OptIn(ExperimentalMaterial3Api::class)

package com.shivtej.owner.ui

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.Dp
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
private data class Hit(val pid: String, val article: String, val colour: String, val size: String, val cats: List<String>, val brand: String = "", val type: String = "")

private fun catsOfProduct(p: Product): List<String> = p.category.split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }

private fun sizeKey(pid: String, colour: String, size: String) = "$pid|$colour|$size"

/** The saved check: category and brand ("" = all), scanned barcodes with their time, hand counts for pieces without a barcode. */
private class CheckState(var cat: String, val scanned: MutableMap<String, Long>, val manual: MutableMap<String, Int>,
    var brand: String = "", var type: String = "", var colour: String = "") {
    fun toJson(): String = JSONObject().put("cat", cat).put("brand", brand).put("type", type).put("colour", colour)
        .put("scanned", JSONObject(scanned.mapValues { it.value }))
        .put("manual", JSONObject(manual.mapValues { it.value })).toString()

    companion object {
        fun from(s: String): CheckState = try {
            val o = JSONObject(s)
            val sc = o.optJSONObject("scanned") ?: JSONObject()
            val mn = o.optJSONObject("manual") ?: JSONObject()
            CheckState(o.optString("cat", ""),
                sc.keys().asSequence().associateWith { sc.optLong(it) }.toMutableMap(),
                mn.keys().asSequence().associateWith { mn.optInt(it) }.toMutableMap(),
                o.optString("brand", ""), o.optString("type", ""), o.optString("colour", ""))
        } catch (e: Exception) { CheckState("", mutableMapOf(), mutableMapOf()) }
    }
}

/** The stock check's sounds are the shared Beeps (ok / again / fail). */
private class Feedback(private val ctx: Context) {
    fun ok() = Beeps.ok(ctx)
    fun again() = Beeps.again(ctx)
    fun notFound() = Beeps.fail(ctx)
    fun release() {}
}

/** Live camera that stays open; every code it reads goes to onCode (the screen decides what to do). */
@androidx.annotation.OptIn(markerClass = [ExperimentalGetImage::class])
@Composable
internal fun ContinuousScanner(height: Dp = 190.dp, onCode: (String) -> Unit) {
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
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxWidth().height(height))
}

/** The "Stock check" screen (dashboard button / menu): opens the check at once, and the button reopens it. */
@Composable
fun StockCheckPage(app: App) {
    var open by remember { mutableStateOf(true) }
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("स्टॉक चेक", fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text("स्कैनर खुला रहेगा। हर स्कैन पर वाइब्रेशन होगा।", fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
        Button(onClick = { open = true }) { Text("स्टॉक चेक खोलें") }
    }
    if (open) StockCheckDialog(app) { open = false }
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
    var soldHit by remember { mutableStateOf<com.shivtej.owner.data.Repo.SoldInfo?>(null) }
    val scopeSold = rememberCoroutineScope()
    var confirmClear by remember { mutableStateOf(false) }
    val feedback = remember(ctx) { Feedback(ctx) }
    DisposableEffect(Unit) { onDispose { feedback.release() } }

    fun save() { app.repo.prefs.stockCheckJson = st.toJson(); tick++ }

    // every barcode -> its size, and the categories of its product
    val index = remember(active) {
        val m = HashMap<String, Hit>()
        active.forEach { p ->
            p.variants.forEach { v ->
                v.sizes.forEach { s ->
                    (s.barcodes.ifEmpty { listOfNotNull(s.barcode.takeIf { it.isNotBlank() }) }).forEach { c ->
                        m[c] = Hit(p.id, p.article.ifBlank { p.name }, v.colour, s.size, catsOfProduct(p), p.brand.trim().uppercase(), p.productType.trim())
                    }
                }
            }
        }
        m
    }
    val allCats = remember(active) { active.flatMap { catsOfProduct(it) }.distinct().sorted() }
    val allBrands = remember(active) { active.map { it.brand.trim().uppercase() }.filter { it.isNotEmpty() }.distinct().sorted() }
    val allTypes = remember(active) { active.map { it.productType.trim() }.filter { it.isNotEmpty() }.distinct().sorted() }
    val allColours = remember(active) { active.flatMap { p -> p.variants.map { it.colour.trim().uppercase() } }.filter { it.isNotEmpty() }.distinct().sorted() }

    // the pieces of the chosen category (or all): expected barcodes per size
    val inScope = remember(active, st.brand, st.type, st.colour, tick) {
        active.filter { p ->
            (st.brand.isEmpty() || p.brand.trim().uppercase() == st.brand) &&
            (st.type.isEmpty() || p.productType.trim() == st.type) &&
            (st.colour.isEmpty() || p.variants.any { it.colour.trim().uppercase() == st.colour })
        }
    }
    data class SizeRow(val pid: String, val article: String, val colour: String, val size: String,
                       val codes: List<String>, val noBarcode: Int)
    val sizeRows = remember(inScope, st.colour) {
        inScope.flatMap { p ->
            p.variants.filter { st.colour.isEmpty() || it.colour.trim().uppercase() == st.colour }.flatMap { v ->
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
    // every piece in scope = barcoded pieces + pieces without barcode (qty not on a barcode)
    val totalPieces = inScopeCodes.size + sizeRows.sumOf { it.noBarcode }
    // hand counts of pieces without barcode count as scanned, up to their number
    val handCounted = sizeRows.filter { it.noBarcode > 0 }.sumOf { r ->
        minOf(r.noBarcode, st.manual[sizeKey(r.pid, r.colour, r.size)] ?: 0)
    }
    val scannedTotal = scannedHere.size + handCounted
    val missingCount = (totalPieces - scannedTotal).coerceAtLeast(0)
    val uncounted = sizeRows.sumOf { r -> r.noBarcode - minOf(r.noBarcode, st.manual[sizeKey(r.pid, r.colour, r.size)] ?: 0) }
    fun gotOf(r: SizeRow): Int = r.codes.count { it in st.scanned } +
        minOf(r.noBarcode, st.manual[sizeKey(r.pid, r.colour, r.size)] ?: 0)
    fun totalOf(r: SizeRow): Int = r.codes.size + r.noBarcode

    fun onCode(code: String) {
        val now = System.currentTimeMillis()
        if (code == lastCode && now - lastAt < 1200) return      // the same label still in view: one scan
        lastCode = code; lastAt = now
        val hit = index[code]
        when {
            hit == null && app.repo.soldInfo(code) != null -> {
                // sold earlier: never counted in stock; ask whether the customer brought it back
                feedback.notFound(); lastRed = true
                val s = app.repo.soldInfo(code)!!
                lastText = "बिका हुआ: ${s.article} · ${s.colour} · ${s.size} (${s.bill})"
                soldHit = s
            }
            hit == null -> {
                feedback.notFound(); lastRed = true
                lastText = "बारकोड नहीं मिला: $code"
            }
            st.brand.isNotEmpty() && st.brand != hit.brand -> {
                feedback.notFound(); lastRed = true
                lastText = "यह आइटम इस ब्रांड का नहीं: ${hit.article} · ${hit.colour} · ${hit.size}"
            }
            st.type.isNotEmpty() && st.type != hit.type -> {
                feedback.notFound(); lastRed = true
                lastText = "यह आइटम इस टाइप का नहीं: ${hit.article} · ${hit.colour} · ${hit.size}"
            }
            st.colour.isNotEmpty() && st.colour != hit.colour.trim().uppercase() -> {
                feedback.notFound(); lastRed = true
                lastText = "यह आइटम इस रंग का नहीं: ${hit.article} · ${hit.colour} · ${hit.size}"
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
    val soldCount = remember(tick) { app.repo.soldAll().size }
    soldHit?.let { s ->
        AlertDialog(
            onDismissRequest = { soldHit = null },
            title = { Text("यह माल बिका हुआ है") },
            text = { Text("${s.article} · ${s.colour} · साइज़ ${s.size}\nबिल: ${s.bill}\nयह स्टॉक में नहीं है, गिनती में नहीं जुड़ेगा।\nकिसी ग्राहक ने वापस किया है तो स्टॉक में जोड़ें।") },
            confirmButton = { TextButton(onClick = {
                soldHit = null
                scopeSold.launch {
                    val ok = runCatching { app.repo.restockSold(s.code) }.getOrDefault(false)
                    lastText = if (ok) "वापसी: स्टॉक में जुड़ा ${s.article} · ${s.colour} · ${s.size}" else "स्टॉक में नहीं जुड़ पाया"
                    lastRed = !ok
                    tick++
                }
            }) { Text("हाँ, वापसी है: स्टॉक में जोड़ें") } },
            dismissButton = { TextButton(onClick = { soldHit = null }) { Text("नहीं, बस देखा") } }
        )
    }
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
            Column(Modifier.fillMaxSize()) {
                // camera on top; the small wordmark, sound and close sit over it, the last result at the bottom
                Box(Modifier.fillMaxWidth().height(190.dp)) {
                    ContinuousScanner(height = 190.dp) { onCode(it) }
                    Row(Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Wordmark()
                        Spacer(Modifier.weight(1f))
                        OverlayChip(if (soundOn) "🔊 चालू" else "🔇 बंद") {
                            soundOn = !soundOn; app.repo.prefs.stockCheckSound = soundOn
                        }
                        Spacer(Modifier.width(6.dp))
                        OverlayChip("बंद करें", onClose)
                    }
                    Text(lastText, color = if (lastRed) Color(0xFFFF8A80) else Color(0xFFA5D6A7),
                        fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                        modifier = Modifier.align(Alignment.BottomStart).padding(10.dp)
                            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp))
                }

                // progress: how much of the stock is scanned
                val pct = if (totalPieces == 0) 0 else scannedTotal * 100 / totalPieces
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("$pct%", fontWeight = FontWeight.ExtraBold, fontSize = 28.sp)
                        Spacer(Modifier.weight(1f))
                        Text("स्कैन $scannedTotal · कुल $totalPieces", fontSize = 13.sp, color = Color(0xFF6E6E73))
                    }
                    LinearProgressIndicator(
                        progress = if (totalPieces == 0) 0f else scannedTotal.toFloat() / totalPieces,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(8.dp),
                        color = Color(0xFF2E7D32), trackColor = Color(0xFFECEEF2))
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Text("बाकी $missingCount", fontSize = 13.sp, color = Color(0xFFC62828), fontWeight = FontWeight.SemiBold)
                        if (soldCount > 0) Text("  · बिका हुआ (गिनती में नहीं): $soldCount", fontSize = 12.sp, color = Color(0xFF8E0000))
                        Spacer(Modifier.weight(1f))
                        Text("हाथ से गिनती: $handCounted", fontSize = 12.sp, color = Color(0xFF6E6E73))
                    }
                }

                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("स्कैन हुए ($scannedTotal)") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("नहीं स्कैन हुए ($missingCount)") })
                }

                // filters: ALL (everything together), then brand, type and colour
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = st.brand.isEmpty() && st.type.isEmpty() && st.colour.isEmpty(),
                        onClick = { st.brand = ""; st.type = ""; st.colour = ""; save() },
                        label = { Text("ALL", maxLines = 1) }, modifier = Modifier.weight(1f))
                    FilterPick("BRAND", st.brand, allBrands, Modifier.weight(1f)) { st.brand = it; save() }
                    FilterPick("Type", st.type, allTypes, Modifier.weight(1f)) { st.type = it; save() }
                    FilterPick("Colour", st.colour, allColours, Modifier.weight(1f)) { st.colour = it; save() }
                }

                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                    if (tab == 0) {
                        // only what is scanned: one article per block, one line per size that has a scan
                        val byItem = sizeRows.groupBy { "${it.article} · ${it.colour}" }
                        byItem.forEach { (title, rows) ->
                            val got = rows.filter { gotOf(it) > 0 }
                            if (got.isNotEmpty()) {
                                item(key = "s_$title") {
                                    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                                }
                                items(got, key = { "s_${it.pid}|${it.colour}|${it.size}" }) { r ->
                                    Text("  साइज़ ${r.size}:  ${gotOf(r)} / ${totalOf(r)}" +
                                        if (r.noBarcode > 0) "  (बिना बारकोड ${r.noBarcode})" else "",
                                        fontSize = 13.sp, modifier = Modifier.padding(vertical = 1.dp))
                                }
                            }
                        }
                        if (byItem.values.none { rows -> rows.any { gotOf(it) > 0 } }) item { Text("अभी कोई आइटम स्कैन नहीं हुआ", fontSize = 13.sp) }
                    } else {
                        val byItem = sizeRows.groupBy { "${it.article} · ${it.colour}" }
                        byItem.forEach { (title, rows) ->
                            val missRows = rows.filter { r -> totalOf(r) - gotOf(r) > 0 }
                            if (missRows.isNotEmpty()) item(key = "m_$title") {
                                Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                            }
                            items(missRows, key = { "m_${it.pid}|${it.colour}|${it.size}" }) { r ->
                                val miss = totalOf(r) - gotOf(r)
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

                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { confirmClear = true }, modifier = Modifier.weight(1f)) { Text("सब साफ़ करें", fontSize = 12.sp) }
                    OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f)) { Text("रोकें (बाद में जारी)", fontSize = 12.sp) }
                    Button(onClick = { confirmApply = true }, modifier = Modifier.weight(1f)) { Text("लागू करें", fontSize = 12.sp) }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
        text = { Text("स्कैन हुए: $scannedTotal पीस, स्टॉक वही रहेगा।\n" +
            "नहीं स्कैन हुए: $missingCount पीस, ये स्टॉक से हट जाएँगे (बिका या गायब मानें)।\n" +
            "इनमें बिना बारकोड और हाथ से न गिने गए: $uncounted।\nसूची एक बार देख लें।") },
        confirmButton = { TextButton(onClick = {
            confirmApply = false
            scope.launch {
                val changed = app.repo.applyStockCheck(inScope, applyList, st.manual.toMap(), st.colour)
                st.scanned.clear(); st.manual.clear(); save()
                toast(ctx, "स्टॉक चेक लागू हुआ: $changed साइज़ बदले")
                onClose()
            }
        }) { Text("हाँ, लागू करें") } },
        dismissButton = { TextButton(onClick = { confirmApply = false }) { Text("रद्द") } })
}


/** A small, plain wordmark over the camera (the shop name only, kept short). */
@Composable
private fun Wordmark() {
    Text("SHIVTEJ", fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, letterSpacing = 2.sp, color = Color(0xFFC62828),
        modifier = Modifier.background(Color.White.copy(alpha = 0.92f), RoundedCornerShape(6.dp)).padding(horizontal = 9.dp, vertical = 4.dp))
}

@Composable
private fun OverlayChip(text: String, onClick: () -> Unit) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
        modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 5.dp))
}

/** One filter button: shows its name, or the chosen value; opens the list of values ("सभी" first). */
@Composable
private fun FilterPick(label: String, value: String, options: List<String>, modifier: Modifier, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        FilterChip(selected = value.isNotEmpty(), onClick = { open = true }, modifier = Modifier.fillMaxWidth(),
            label = { Text(if (value.isEmpty()) label else value, maxLines = 1, overflow = TextOverflow.Ellipsis) })
    }
    // A plain list in a dialog (not a DropdownMenu with a scroll inside): the old menu crashed the app when opened.
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(label) },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                item { TextButton(onClick = { open = false; onPick("") }, modifier = Modifier.fillMaxWidth()) { Text("सभी", modifier = Modifier.fillMaxWidth()) } }
                items(options) { o ->
                    TextButton(onClick = { open = false; onPick(o) }, modifier = Modifier.fillMaxWidth()) { Text(o, modifier = Modifier.fillMaxWidth()) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text("बंद करें") } }
    )
}
