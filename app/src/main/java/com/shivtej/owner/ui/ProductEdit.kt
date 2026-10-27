@file:OptIn(ExperimentalMaterial3Api::class)

package com.shivtej.owner.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shivtej.owner.App
import com.shivtej.owner.data.CatType
import com.shivtej.owner.data.PRODUCT_TYPE_START
import com.shivtej.owner.data.Product
import com.shivtej.owner.data.capName
import com.shivtej.owner.data.SizePreset
import com.shivtej.owner.data.SizePresets
import com.shivtej.owner.data.SizeStock
import com.shivtej.owner.data.Variant
import com.shivtej.owner.util.Images
import com.shivtej.owner.util.Videos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** One size of one colour. Every piece has its own barcode: qty 3 means 3 barcodes in [codes]. */
class SizeDraft(size: String = "", qty: String = "0", initial: List<String> = emptyList(), mrp: String = "", price: String = "") {
    var size by mutableStateOf(size)
    var qty by mutableStateOf(qty)
    var mrp by mutableStateOf(mrp)              // Extra size groups only: MRP for this size; empty = the product MRP
    var price by mutableStateOf(price)          // Extra size groups only: selling price; empty = the product price
    val codes = mutableStateListOf<String>().apply { addAll(initial.map { it.trim() }.filter { it.isNotEmpty() }) }
    var scanText by mutableStateOf("")          // what the scanner typed into this size's scan box
    var scanJob: Job? = null                    // waits a moment so the whole code is in before it is added
    val scanFocus = FocusRequester()            // the scan box of this size
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
    var productType by mutableStateOf("")
    var subCategory by mutableStateOf("")   // category under the product type (e.g. V BELT under चप्पल)
    var description by mutableStateOf("")
    var mrp by mutableStateOf("")
    var price by mutableStateOf("")
    var active by mutableStateOf(true)
    var newItem by mutableStateOf(true)   // a new product is NEW by default; the owner can switch it off
    val images = mutableStateListOf<String>()
    val videos = mutableStateListOf<String>()
    val variants = mutableStateListOf<VariantDraft>()

    fun fill(p: Product) {
        article = p.article; name = p.name; brand = p.brand; category = p.category; description = p.description
        productType = p.productType
        subCategory = p.subCategory
        mrp = p.mrp.toBigDecimal().stripTrailingZeros().toPlainString()
        price = p.price.toBigDecimal().stripTrailingZeros().toPlainString()
        active = p.active; newItem = p.newItem
        images.clear(); images.addAll(p.images)
        videos.clear(); videos.addAll(p.videos)
        variants.clear()
        p.variants.forEach { v ->
            variants.add(VariantDraft(v.colour, v.images, v.sizes.map {
                SizeDraft(it.size, it.qty.toString(), it.barcodes.ifEmpty { listOfNotNull(it.barcode.takeIf { b -> b.isNotBlank() }) },
                    mrpText(it.mrp), mrpText(it.price))
            }))
        }
    }

    /** Every barcode already on this product (so new ones never repeat them). */
    fun takenCodes(): MutableSet<String> =
        variants.flatMap { it.sizes }.flatMap { it.codes }.map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()

    fun build() = Product(
        id, article.trim(), name.trim(), brand.trim(), category.trim(), description.trim(),
        mrp.toDoubleOrNull() ?: 0.0, price.toDoubleOrNull() ?: 0.0, active, newItem, images.toList(),
        variants.map { v ->
            Variant(v.colour.trim(), v.images.toList(),
                v.sizes.map { SizeStock(it.size.trim(), it.qty.toIntOrNull() ?: 0, it.codes.firstOrNull() ?: "", it.codes.toList(),
                    it.mrp.toDoubleOrNull()?.takeIf { m -> m > 0 } ?: 0.0,
                    it.price.toDoubleOrNull()?.takeIf { p -> p > 0 } ?: 0.0) })
        },
        videos.toList(),
        productType.trim(),
        subCategory = subCategory.trim()
    )
}

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
    var brandList by remember { mutableStateOf<List<String>>(emptyList()) }
    var typeList by remember { mutableStateOf<List<String>>(PRODUCT_TYPE_START) }
    val categoryTree = app.repo.categoryTree()   // read again on every change, so a new category shows at once
    var articleList by remember { mutableStateOf<List<String>>(emptyList()) }
    var colourList by remember { mutableStateOf<List<String>>(COLOUR_START) }
    var categories by remember { mutableStateOf(categoriesFrom(app.repo.prefs.categoryList)) }
    var reorderingCategories by remember { mutableStateOf(false) }
    // update 21: the saved size groups ("+ Custom" / "+ Extra size") and the dialogs that use them
    var presets by remember { mutableStateOf(app.repo.sizePresets()) }
    var groupFor by remember { mutableStateOf<Pair<VariantDraft, String>?>(null) }   // colour + kind of the group dialog
    var extraFor by remember { mutableStateOf<VariantDraft?>(null) }                 // colour for the Small / Big chooser

    LaunchedEffect(id) {
        if (id != null) { app.repo.load(id)?.let { draft.fill(it) }; loaded = true }
    }
    LaunchedEffect(Unit) {
        brandList = app.repo.knownBrands()
        typeList = (app.repo.knownProductTypes() + app.repo.categoryTree().map { it.name }).distinctBy { it.lowercase() }
        articleList = app.repo.knownArticles()
        colourList = (COLOUR_START + app.repo.products.first().flatMap { p -> p.variants.map { it.colour.trim() } })
            .map { it.trim().uppercase() }.filter { it.isNotEmpty() }.distinct()
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
        if (reorderingCategories) {
            CategoryOrderDialog(categories,
                onSave = { list ->
                    app.repo.prefs.categoryList = list.joinToString(",")
                    categories = list
                    reorderingCategories = false
                },
                onCancel = { reorderingCategories = false })
        }

        SectionTitle("Photos")
        ImageRow(draft.images, { pickGallery(draft.images) }, { takePhoto(draft.images) })

        SectionTitle("Videos (optional, up to 25 MB each)")
        VideoList(draft.videos, onAdd = { pickVideos(-1) }, onReplace = { i -> pickVideos(i) })

        SectionTitle("Product details")
        ArticleField(draft.article, articleList) { draft.article = it }
        BrandField(draft.brand, brandList) { draft.brand = it }
        TypeField(draft.productType, typeList) { draft.productType = it }
        SubCategoryField(draft.productType, draft.subCategory, categoryTree) { draft.subCategory = it }
        CategoryField(draft.category, categories,
            onChange = { draft.category = it },
            onChangeOrder = { reorderingCategories = true })
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
                        Column(Modifier.weight(1f)) {
                            SuggestField("Colour *", v.colour, suggestionsFor(v.colour, colourList)) { v.colour = it }
                        }
                        IconButton(onClick = {
                            pendingRemove = "Remove colour \"${v.colour}\" with all its sizes and stock?" to { draft.variants.remove(v); Unit }
                        }) { Icon(Icons.Filled.Delete, "Remove colour") }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Photos of this colour", fontSize = 12.sp)
                    ImageRow(v.images, { pickGallery(v.images) }, { takePhoto(v.images) })
                    Spacer(Modifier.height(8.dp))
                    Text("Sizes", fontWeight = FontWeight.SemiBold)
                    // a scanned piece is added to the size whose scan box has the focus; no button press needed
                    /** The size after this one (wrapping around) that still needs pieces, or null when every size is full. */
                    fun nextOpenSize(after: SizeDraft): SizeDraft? {
                        val rest = v.sizes.dropWhile { it !== after }.drop(1) + v.sizes.takeWhile { it !== after }
                        return rest.firstOrNull { (it.qty.toIntOrNull() ?: 0) > it.codes.size }
                    }
                    // puts the cursor back on this size's scan box, so the next piece goes to the same size
                    fun keepHere(size: SizeDraft) { runCatching { size.scanFocus.requestFocus() } }
                    fun acceptScan(size: SizeDraft, code: String) {
                        size.scanText = ""
                        if (code.isEmpty()) return
                        val want = size.qty.toIntOrNull() ?: 0
                        if (want > 0 && size.codes.size >= want) {
                            // this size already has all its pieces: the code goes to the next size that still needs one
                            val next = nextOpenSize(size)
                            if (next == null) toast(ctx, "All pieces are already scanned")
                            else acceptScan(next, code)
                            return
                        }
                        if (size.codes.contains(code)) { toast(ctx, "This barcode is already on this size"); keepHere(size); return }
                        if (draft.takenCodes().contains(code)) { toast(ctx, "This barcode is already used on this product"); keepHere(size); return }
                        size.codes.add(code)
                        if (want > 0 && size.codes.size >= want) {
                            // all pieces of this size are in: the scanner moves on to the next size (in list order) by itself
                            nextOpenSize(size)?.let { next -> runCatching { next.scanFocus.requestFocus() } }
                        } else {
                            // not full yet: stay on this size until all its pieces are scanned
                            keepHere(size)
                        }
                    }
                    v.sizes.forEach { s ->
                        Column(Modifier.padding(top = 6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(s.size, { s.size = it }, label = { Text("Size") }, singleLine = true,
                                    modifier = Modifier.width(78.dp))
                                Spacer(Modifier.width(6.dp))
                                OutlinedTextField(s.qty, { s.qty = it.filter(Char::isDigit) }, label = { Text("Qty") }, singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.width(78.dp))
                                Spacer(Modifier.weight(1f))
                                IconButton(onClick = { withCamera { scanTarget = s } }) { Icon(Icons.Filled.QrCodeScanner, "Scan barcode") }
                                IconButton(onClick = {
                                    if ((s.qty.toIntOrNull() ?: 0) > 0)
                                        pendingRemove = "Remove size ${s.size} (${s.qty} in stock) from ${v.colour}?" to { v.sizes.remove(s); Unit }
                                    else v.sizes.remove(s)
                                }) { Icon(Icons.Filled.Close, "Remove size") }
                            }
                            // the scanner types here. A full code is added to this size after a short pause (or Enter)
                            OutlinedTextField(
                                s.scanText,
                                { typed ->
                                    s.scanText = typed
                                    s.scanJob?.cancel()
                                    val code = typed.trim()
                                    if (isScannedCode(code))
                                        s.scanJob = scope.launch { delay(500); acceptScan(s, code) }
                                },
                                label = { Text("Scan barcode for this size") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = {
                                    s.scanJob?.cancel()
                                    acceptScan(s, s.scanText.trim())
                                }),
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).focusRequester(s.scanFocus)
                            )
                            // one barcode per piece: qty 3 = 3 barcodes, each scanned or made separately
                            if (s.price.isNotBlank()) Text(
                                "MRP ₹${s.mrp.ifBlank { "-" }}  ·  Selling ₹${s.price}",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val want = s.qty.toIntOrNull() ?: 0
                            Text("Barcodes: ${s.codes.size} of $want pieces", fontSize = 12.sp,
                                color = if (s.codes.size == want) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                            s.codes.forEach { code ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(code, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(start = 4.dp))
                                    IconButton(onClick = { s.codes.remove(code) }) { Icon(Icons.Filled.Close, "Remove barcode") }
                                }
                            }
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AssistChip(onClick = { v.sizes.add(SizeDraft()) }, label = { Text("+ Size") })
                        AssistChip(onClick = { groupFor = v to SizePresets.NORMAL }, label = { Text("+ Custom") })
                        AssistChip(onClick = { extraFor = v }, label = { Text("+ Extra size") })
                    }
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
    groupFor?.let { (v, kind) ->
        val saveAll: (List<SizePreset>) -> Unit = { list -> presets = list; scope.launch { app.repo.saveSizePresets(list) } }
        if (kind == SizePresets.NORMAL) SizeGroupsDialog(kind, presets,
            onPick = { p -> applySizeGroup(v, p); groupFor = null },
            onSaveAll = saveAll,
            onDismiss = { groupFor = null })
        else ExtraSizeDialog(kind, presets,
            onApply = { p -> applyExtraSizes(v, p); groupFor = null },
            onSaveAll = saveAll,
            onDismiss = { groupFor = null })
    }
    extraFor?.let { v ->
        SizeKindDialog(onPick = { kind -> extraFor = null; groupFor = v to kind }, onDismiss = { extraFor = null })
    }
    scanTarget?.let { s ->
        ScannerDialog(onResult = { code ->
            val c = code.trim()
            when {
                c.isEmpty() -> Unit
                s.codes.contains(c) -> toast(ctx, "This barcode is already on this size")
                else -> s.codes.add(c)
            }
            scanTarget = null
        }, onDismiss = { scanTarget = null })
    }
}

@Composable
internal fun Field(
    label: String, value: String, modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text, single: Boolean = true, onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = single,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)
    )
}

/** A scanner's code: no spaces, 8 or more characters, with a digit. A typed name is never looked up as a code. */
internal fun isScannedCode(t: String): Boolean = t.length >= 8 && t.none { it.isWhitespace() } && t.any { it.isDigit() }

/** Colours offered while typing; the colours already saved on products are added to these. */
private val COLOUR_START = listOf("BLACK", "BROWN", "TAN", "OLIVE", "WHITE", "NAVY", "BLUE", "RED", "MAROON", "GREY", "BEIGE", "GREEN", "CREAM", "MUSTARD", "PINK", "ORANGE", "YELLOW", "PURPLE", "LAVENDER", "WINE", "D.BRN", "W.CREAM")

/** Suggestions while typing: saved values that start with what is typed (then those that contain it). Tap one to fill the field. */
internal fun suggestionsFor(typed: String, known: List<String>): List<String> {
    val q = typed.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    fun words(s: String) = s.lowercase().split(' ', '-', '/', '(', ')', '.').filter { it.isNotEmpty() }
    val start = known.filter { it.lowercase().startsWith(q) }
    val word = known.filter { k -> !k.lowercase().startsWith(q) && words(k).any { it.startsWith(q) } }
    val contains = known.filter { k -> !k.lowercase().startsWith(q) && words(k).none { it.startsWith(q) } && k.lowercase().contains(q) }
    return (start + word + contains).filter { it.lowercase() != q }.distinct().take(8)
}

/** A text field with its suggestion list under it. Nothing is forced: after a tap the text can still be changed (2001 to 2002). */
@Composable
private fun SuggestField(label: String, value: String, matches: List<String>, onChange: (String) -> Unit) {
    Column {
        Field(label, value, onChange = onChange)
        if (matches.isNotEmpty()) {
            Card(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                Column {
                    matches.forEach { m ->
                        Text(m, fontSize = 15.sp,
                            modifier = Modifier.fillMaxWidth().clickable { onChange(m) }.padding(horizontal = 12.dp, vertical = 10.dp))
                    }
                }
            }
        }
    }
}

/** Brand field: brands already saved on products. */
@Composable
private fun BrandField(value: String, known: List<String>, onChange: (String) -> Unit) =
    SuggestField("Brand", value, suggestionsFor(value, known), onChange)

/** Article field: article numbers already saved on products. */
@Composable
private fun ArticleField(value: String, known: List<String>, onChange: (String) -> Unit) =
    SuggestField("Article *", value, suggestionsFor(value, known), onChange)

/** Product type (चप्पल, सैंडल, शूज़ ...): saved types and the starter types, also found by their Latin spelling. */
@Composable
private fun TypeField(value: String, known: List<String>, onChange: (String) -> Unit) {
    // suggestions are English only (the Hindi / Marathi starter names are not offered)
    val english = known.filter { t -> t.none { it in '\u0900'..'\u097F' } }
    val matches = suggestionsFor(value, english)
    SuggestField("Product type (चप्पल / सैंडल / शूज़ ...)", value, matches, onChange)
}

/** Category under the product type: the suggestions are the categories saved for that type (CATEGORY button on the Products screen). */
@Composable
private fun SubCategoryField(type: String, value: String, tree: List<CatType>, onChange: (String) -> Unit) {
    val cats = tree.firstOrNull { it.name == type.trim() }?.cats ?: emptyList()
    val q = value.trim().lowercase()
    val matches = if (q.isEmpty()) cats.take(8) else suggestionsFor(value, cats)
    SuggestField("Category (under the product type)", value, matches) { onChange(it.uppercase()) }
}

/**
 * Category field: tick one or more categories (a product can be in both "Men" and "Senior Men").
 * The menu stays open while you tick. "Change order" moves the categories up or down (saved permanently).
 * The value is saved as one text, for example "Men, Senior Men"; the website shows the product under each of them.
 */
@Composable
private fun CategoryField(value: String, categories: List<String>, onChange: (String) -> Unit, onChangeOrder: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val selected = value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        OutlinedTextField(
            value = value, onValueChange = {}, readOnly = true, singleLine = true,
            label = { Text("Category (Men / Ladies / Boy / Girl ...)") },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) },
            modifier = Modifier.fillMaxWidth()
        )
        Box(Modifier.matchParentSize().clickable { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            categories.forEach { c ->
                val on = c in selected
                DropdownMenuItem(text = { Text((if (on) "✓  " else "      ") + c) }, onClick = {
                    val next = if (on) selected - c else selected + c
                    // the order of the category list is kept; a name that is not in the list goes at the end
                    onChange((categories.filter { it in next } + next.filter { it !in categories }).joinToString(", "))
                })
            }
            DropdownMenuItem(text = { Text("Change order (up / down)") }, onClick = { open = false; onChangeOrder() })
        }
    }
}

/** Up and down arrows for the category list. Nothing is added or removed here. */
@Composable
private fun CategoryOrderDialog(initial: List<String>, onSave: (List<String>) -> Unit, onCancel: () -> Unit) {
    var list by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Category order") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Top to bottom, as in the dropdown. Use the arrows to change the order.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                list.forEachIndexed { i, c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(c, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        IconButton(onClick = { list = moveCategory(list, i, i - 1) }, enabled = i > 0) {
                            Icon(Icons.Filled.KeyboardArrowUp, "Move up")
                        }
                        IconButton(onClick = { list = moveCategory(list, i, i + 1) }, enabled = i < list.lastIndex) {
                            Icon(Icons.Filled.KeyboardArrowDown, "Move down")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(list) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}

/** Dropdown categories in CAPITAL letters; KIDS is added for phones that were set up before it existed (before BOY). */
private fun categoriesFrom(csv: String): List<String> {
    val list = csv.split(",").map { capName(it) }.filter { it.isNotEmpty() }.distinct().toMutableList()
    if ("KIDS" !in list) { val at = list.indexOf("BOY"); if (at >= 0) list.add(at, "KIDS") else list.add("KIDS") }
    return list
}

private fun moveCategory(list: List<String>, i: Int, j: Int): List<String> {
    if (i !in list.indices || j !in list.indices) return list
    val m = list.toMutableList()
    val t = m[i]; m[i] = m[j]; m[j] = t
    return m
}

/** Videos of one product: preview in the phone's video player, replace or remove. Shared with the website later. */
@Composable
private fun VideoList(list: SnapshotStateList<String>, onAdd: () -> Unit, onReplace: (Int) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        list.toList().forEachIndexed { i, src ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.PlayCircle, null, tint = BrandRed, modifier = Modifier.size(36.dp))
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text("Video ${i + 1}", fontWeight = FontWeight.SemiBold)
                            val online = src.startsWith("http") || src.startsWith("products/")
                            Text(if (online) "Saved online" else "On this phone - uploads when online",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { scope.launch { previewVideo(ctx, src) } }) { Text("Preview") }
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
private suspend fun previewVideo(ctx: android.content.Context, src: String) {
    try {
        val uri = when {
            src.startsWith("http") -> android.net.Uri.parse(src)
            // stored in the private B2 bucket: open a short signed link
            src.startsWith("products/") -> {
                val url = privatePhotoUrl(src)
                if (url == null) { toast(ctx, "This video can not be opened now. Check the internet and try again."); return }
                android.net.Uri.parse(url)
            }
            else -> {
                val f = File(src)
                if (!f.exists()) { toast(ctx, "This video file is missing on this phone. Replace it."); return }
                androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
            }
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
