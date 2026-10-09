@file:OptIn(ExperimentalMaterial3Api::class)

package com.shivtej.owner.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shivtej.owner.App
import com.shivtej.owner.data.Product
import com.shivtej.owner.data.SizeStock
import com.shivtej.owner.data.Variant
import com.shivtej.owner.util.Images
import com.shivtej.owner.util.Videos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class SizeDraft(size: String = "", qty: String = "0", barcode: String = "") {
    var size by mutableStateOf(size)
    var qty by mutableStateOf(qty)
    var barcode by mutableStateOf(barcode)
}

class VariantDraft(colour: String = "", images: List<String> = emptyList(), sizes: List<SizeDraft> = emptyList()) {
    var colour by mutableStateOf(colour)
    val images = mutableStateListOf<String>().apply { addAll(images) }
    val sizes = mutableStateListOf<SizeDraft>().apply { addAll(sizes) }
}

class ProductDraft(val id: String) {
    var article by mutableStateOf("")
    var name by mutableStateOf("")
    var brand by mutableStateOf("")
    var category by mutableStateOf("")
    var description by mutableStateOf("")
    var mrp by mutableStateOf("")
    var price by mutableStateOf("")
    var active by mutableStateOf(true)
    var newItem by mutableStateOf(false)
    val images = mutableStateListOf<String>()
    val videos = mutableStateListOf<String>()
    val variants = mutableStateListOf<VariantDraft>()

    fun fill(p: Product) {
        article = p.article; name = p.name; brand = p.brand; category = p.category; description = p.description
        mrp = p.mrp.toBigDecimal().stripTrailingZeros().toPlainString()
        price = p.price.toBigDecimal().stripTrailingZeros().toPlainString()
        active = p.active; newItem = p.newItem
        images.clear(); images.addAll(p.images)
        videos.clear(); videos.addAll(p.videos)
        variants.clear()
        p.variants.forEach { v ->
            variants.add(VariantDraft(v.colour, v.images, v.sizes.map { SizeDraft(it.size, it.qty.toString(), it.barcode) }))
        }
    }

    fun build() = Product(
        id, article.trim(), name.trim(), brand.trim(), category.trim(), description.trim(),
        mrp.toDoubleOrNull() ?: 0.0, price.toDoubleOrNull() ?: 0.0, active, newItem, images.toList(),
        variants.map { v ->
            Variant(v.colour.trim(), v.images.toList(),
                v.sizes.map { SizeStock(it.size.trim(), it.qty.toIntOrNull() ?: 0, it.barcode.trim()) })
        },
        videos.toList()
    )
}

private val SIZE_PRESETS = listOf(
    "Men 6-10" to listOf("6", "7", "8", "9", "10"),
    "Ladies 36-40" to listOf("36", "37", "38", "39", "40"),
    "Kids 11-3" to listOf("11", "12", "13", "1", "2", "3"),
    "Small kids 15-20" to listOf("15", "16", "17", "18", "19", "20")
)

@Composable
fun ProductEditScreen(app: App, nav: Nav, id: String?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val draft = remember { ProductDraft(id ?: UUID.randomUUID().toString()) }
    var loaded by remember { mutableStateOf(id == null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pendingRemove by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var scanTarget by remember { mutableStateOf<SizeDraft?>(null) }
    val withCamera = rememberWithCamera()

    LaunchedEffect(id) {
        if (id != null) { app.repo.load(id)?.let { draft.fill(it) }; loaded = true }
    }

    // ---- image pickers
    var target by remember { mutableStateOf<SnapshotStateList<String>?>(null) }
    var camFile by remember { mutableStateOf<File?>(null) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        uris.forEach { u -> Images.importUri(ctx, u)?.let { target?.add(it) } }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = camFile
        if (ok && f != null) {
            Images.importUri(ctx, Uri.fromFile(f))?.let { target?.add(it) }
            f.delete()
        }
    }
    fun pickGallery(list: SnapshotStateList<String>) {
        target = list
        gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    fun takePhoto(list: SnapshotStateList<String>) {
        withCamera {
            target = list
            val (f, uri) = Images.newCameraTarget(ctx)
            camFile = f
            camera.launch(uri)
        }
    }

    // ---- video picker (gallery only). Replace uses the position of the video that was tapped.
    var videoReplaceAt by remember { mutableStateOf(-1) }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(5)) { uris ->
        val at = videoReplaceAt
        videoReplaceAt = -1
        if (uris.isNotEmpty()) scope.launch {
            var first = true
            for (u in uris) {
                try {
                    val path = withContext(Dispatchers.IO) { Videos.importUri(ctx, u) }
                    if (path == null) toast(ctx, "This video can not be read.")
                    else if (at >= 0 && first && at < draft.videos.size) draft.videos[at] = path
                    else draft.videos.add(path)
                } catch (e: IllegalArgumentException) {
                    toast(ctx, e.message ?: Videos.TOO_BIG)
                }
                first = false
            }
        }
    }
    fun pickVideos(replaceAt: Int) {
        videoReplaceAt = replaceAt
        videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
    }

    if (!loaded) { EmptyState("Loading..."); return }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        SectionTitle("Photos")
        ImageRow(draft.images, { pickGallery(draft.images) }, { takePhoto(draft.images) })

        SectionTitle("Videos (optional, up to 25 MB each)")
        VideoList(draft.videos, onAdd = { pickVideos(-1) }, onReplace = { i -> pickVideos(i) })

        SectionTitle("Product details")
        Field("Product name *", draft.name) { draft.name = it }
        Field("Article *", draft.article) { draft.article = it }
        Field("Brand", draft.brand) { draft.brand = it }
        Field("Category (Men / Ladies / Kids ...)", draft.category) { draft.category = it }
        Field("Description", draft.description, single = false) { draft.description = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("MRP", draft.mrp, Modifier.weight(1f), KeyboardType.Decimal) { draft.mrp = it }
            Field("Selling price *", draft.price, Modifier.weight(1f), KeyboardType.Decimal) { draft.price = it }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(draft.active, { draft.active = it }); Spacer(Modifier.width(8.dp)); Text("Active (visible for sale)")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(draft.newItem, { draft.newItem = it }); Spacer(Modifier.width(8.dp)); Text("New item")
        }

        SectionTitle("Colours, sizes and stock")
        Text("Each colour has its own sizes and stock.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        draft.variants.forEach { v ->
            Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(v.colour, { v.colour = it }, label = { Text("Colour *") }, singleLine = true,
                            modifier = Modifier.weight(1f))
                        IconButton(onClick = {
                            pendingRemove = "Remove colour \"${v.colour}\" with all its sizes and stock?" to { draft.variants.remove(v); Unit }
                        }) { Icon(Icons.Filled.Delete, "Remove colour") }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Photos of this colour", fontSize = 12.sp)
                    ImageRow(v.images, { pickGallery(v.images) }, { takePhoto(v.images) })
                    Spacer(Modifier.height(8.dp))
                    Text("Sizes", fontWeight = FontWeight.SemiBold)
                    v.sizes.forEach { s ->
                        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(s.size, { s.size = it }, label = { Text("Size") }, singleLine = true,
                                modifier = Modifier.width(78.dp))
                            Spacer(Modifier.width(6.dp))
                            OutlinedTextField(s.qty, { s.qty = it.filter(Char::isDigit) }, label = { Text("Qty") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.width(78.dp))
                            Spacer(Modifier.width(6.dp))
                            OutlinedTextField(s.barcode, { s.barcode = it }, label = { Text("Barcode") }, singleLine = true,
                                modifier = Modifier.weight(1f))
                            IconButton(onClick = { withCamera { scanTarget = s } }) { Icon(Icons.Filled.QrCodeScanner, "Scan barcode") }
                            IconButton(onClick = {
                                if ((s.qty.toIntOrNull() ?: 0) > 0)
                                    pendingRemove = "Remove size ${s.size} (${s.qty} in stock) from ${v.colour}?" to { v.sizes.remove(s); Unit }
                                else v.sizes.remove(s)
                            }) { Icon(Icons.Filled.Close, "Remove size") }
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AssistChip(onClick = { v.sizes.add(SizeDraft()) }, label = { Text("+ Size") })
                        SIZE_PRESETS.forEach { (label, list) ->
                            AssistChip(onClick = {
                                list.forEach { sz -> if (v.sizes.none { it.size == sz }) v.sizes.add(SizeDraft(sz, "0")) }
                            }, label = { Text("+ $label") })
                        }
                    }
                    TextButton(onClick = {
                        // generate a barcode for every size of this colour that has none
                        scope.launch {
                            val taken = draft.variants.flatMap { it.sizes }.map { it.barcode.trim() }.filter { it.isNotEmpty() }.toMutableSet()
                            v.sizes.forEach { s ->
                                if (s.barcode.isBlank()) { val b = app.repo.newBarcode(taken); taken.add(b); s.barcode = b }
                            }
                        }
                    }) { Text("Generate missing barcodes") }
                }
            }
        }
        OutlinedButton(onClick = { draft.variants.add(VariantDraft()) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Add colour")
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 10.dp)) }
        Spacer(Modifier.height(16.dp))
        Button(
            modifier = Modifier.fillMaxWidth().height(54.dp),
            onClick = {
                scope.launch {
                    try {
                        val taken = draft.variants.flatMap { it.sizes }.map { it.barcode.trim() }.filter { it.isNotEmpty() }.toMutableSet()
                        draft.variants.forEach { v -> v.sizes.forEach { s ->
                            if (s.barcode.isBlank()) { val b = app.repo.newBarcode(taken); taken.add(b); s.barcode = b }
                        } }
                        app.repo.saveProduct(draft.build(), isNew = id == null)
                        toast(ctx, "Saved")
                        nav.back()
                    } catch (e: IllegalArgumentException) {
                        error = e.message
                    }
                }
            }
        ) { Text(if (id == null) "Save item" else "Save changes") }

        if (id != null) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { confirmDelete = true },
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) { Text("Delete product", color = RedOut) }
        }
        Spacer(Modifier.height(40.dp))
    }

    if (confirmDelete) ConfirmDialog(
        "Delete product?", "\"${draft.name}\" and all its colours, sizes and stock will be removed.", "Delete",
        { scope.launch { app.repo.deleteProduct(draft.id); nav.back() } }, { confirmDelete = false }
    )
    pendingRemove?.let { (msg, action) ->
        ConfirmDialog("Remove?", msg, "Remove", { action() }, { pendingRemove = null })
    }
    scanTarget?.let { s ->
        ScannerDialog(onResult = { s.barcode = it; scanTarget = null }, onDismiss = { scanTarget = null })
    }
}

@Composable
private fun Field(
    label: String, value: String, modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text, single: Boolean = true, onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = single,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)
    )
}

/** Videos of one product: preview in the phone's video player, replace or remove. Shared with the website later. */
@Composable
private fun VideoList(list: SnapshotStateList<String>, onAdd: () -> Unit, onReplace: (Int) -> Unit) {
    val ctx = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        list.toList().forEachIndexed { i, src ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.PlayCircle, null, tint = BrandRed, modifier = Modifier.size(36.dp))
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text("Video ${i + 1}", fontWeight = FontWeight.SemiBold)
                            Text(if (src.startsWith("http")) "Saved online" else "On this phone - uploads when online",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { previewVideo(ctx, src) }) { Text("Preview") }
                        TextButton(onClick = { onReplace(i) }) { Text("Replace") }
                        TextButton(onClick = { list.remove(src) }) { Text("Remove", color = RedOut) }
                    }
                }
            }
        }
        OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Videocam, null); Spacer(Modifier.width(6.dp)); Text("Add videos")
        }
    }
}

/** Opens a video in the phone's own video player (there is no in-app player yet). */
private fun previewVideo(ctx: android.content.Context, src: String) {
    try {
        val uri = if (src.startsWith("http")) android.net.Uri.parse(src) else {
            val f = File(src)
            if (!f.exists()) { toast(ctx, "This video file is missing on this phone. Replace it."); return }
            androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        }
        val i = android.content.Intent(android.content.Intent.ACTION_VIEW)
            .setDataAndType(uri, "video/*")
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(i)
    } catch (e: Exception) {
        toast(ctx, "No video player app found on this phone.")
    }
}

@Composable
private fun ImageRow(list: SnapshotStateList<String>, onGallery: () -> Unit, onCamera: () -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        list.toList().forEach { src ->
            Box {
                Thumb(src, 72.dp)
                IconButton(onClick = { list.remove(src) }, modifier = Modifier.size(28.dp).align(Alignment.TopEnd)) {
                    Icon(Icons.Filled.Cancel, "Remove photo", tint = RedOut)
                }
            }
        }
        OutlinedButton(onClick = onGallery) { Icon(Icons.Filled.PhotoLibrary, null); Spacer(Modifier.width(4.dp)); Text("Gallery") }
        OutlinedButton(onClick = onCamera) { Icon(Icons.Filled.PhotoCamera, null); Spacer(Modifier.width(4.dp)); Text("Camera") }
    }
}
