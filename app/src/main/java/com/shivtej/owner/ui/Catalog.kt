@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.shivtej.owner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.shivtej.owner.App
import com.shivtej.owner.data.Product
import kotlin.math.ceil
import kotlin.math.floor

/*
 * Catalog: the products that are on the website (active and with stock), with the same filters as the website
 * (search, category, product type, brand, size, colour, price range, new only, sort).
 * Only the MRP is shown - never the selling price or a discount - so it can be shown to a customer in the shop.
 */

/** The MRP shown in the catalog. When no MRP was entered, the rate is used (same rule as the offline bill). */
fun catalogMrp(p: Product): Double = if (p.mrp > 0) p.mrp else p.price

/** Category names of a product ("Men, Senior Men" = two categories), written like on the website ("senior men" -> "Senior Men"). */
private fun catsOf(p: Product): List<String> =
    p.category.split(",").map { c -> c.trim().lowercase().split(" ").filter { it.isNotEmpty() }
        .joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } } }.filter { it.isNotEmpty() }

/** Kids shows Kids, Boy and Girl products (as on the website); other categories match exactly. */
private val KIDS_CATS = listOf("Kids", "Boy", "Girl")
private fun inCat(p: Product, cat: String): Boolean =
    if (cat == "Kids") catsOf(p).any { it in KIDS_CATS } else cat in catsOf(p)

/** Search text without spaces, dashes and dots, so IG-251 = IG 251 = IG251 (as on the website). */
private fun normQ(s: String): String = s.lowercase().replace(Regex("[^a-z0-9\\u0900-\\u097F]+"), "")

private val COLOUR_HEX: Map<String, Long> = mapOf(
    "red" to 0xFFC62828, "लाल" to 0xFFC62828, "blue" to 0xFF1E63C8, "नीला" to 0xFF1E63C8, "नीले" to 0xFF1E63C8,
    "navy" to 0xFF1F2F5A, "नेवी" to 0xFF1F2F5A, "brown" to 0xFF7B4A2A, "ब्राउन" to 0xFF7B4A2A, "भूरा" to 0xFF7B4A2A,
    "olive" to 0xFF6B7A2A, "ऑलिव" to 0xFF6B7A2A, "ऑलिव्ह" to 0xFF6B7A2A, "black" to 0xFF111111, "काला" to 0xFF111111,
    "white" to 0xFFFFFFFF, "सफ़ेद" to 0xFFFFFFFF, "सफेद" to 0xFFFFFFFF, "grey" to 0xFF8A8A8A, "gray" to 0xFF8A8A8A,
    "ग्रे" to 0xFF8A8A8A, "green" to 0xFF2E8B57, "हरा" to 0xFF2E8B57, "yellow" to 0xFFF2C200, "पीला" to 0xFFF2C200,
    "maroon" to 0xFF800000, "मैरून" to 0xFF800000, "pink" to 0xFFE77FA6, "गुलाबी" to 0xFFE77FA6,
    "orange" to 0xFFF08A24, "नारंगी" to 0xFFF08A24, "beige" to 0xFFD8C3A5, "बेज" to 0xFFD8C3A5, "tan" to 0xFFC69C6D,
    "cream" to 0xFFF3E9D2, "क्रीम" to 0xFFF3E9D2, "purple" to 0xFF7B3FA0, "बैंगनी" to 0xFF7B3FA0,
    "gold" to 0xFFD4A017, "सुनहरा" to 0xFFD4A017, "silver" to 0xFFBFC3C8, "चांदी" to 0xFFBFC3C8
)
private fun colourOf(name: String): Color = Color(COLOUR_HEX[name.trim().lowercase()] ?: 0xFFC9C9C9)

private val sizeOrder: (String) -> Double = { it.toDoubleOrNull() ?: 9999.0 }

@Composable
fun CatalogScreen(app: App) {
    val all by app.repo.products.collectAsStateWithLifecycle(emptyList())
    // same products as the website: switched on, and at least one size in stock
    val products = remember(all) { all.filter { p -> p.active && p.variants.any { v -> v.sizes.any { it.qty > 0 } } } }

    var query by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf("All") }
    var type by remember { mutableStateOf("") }
    var brand by remember { mutableStateOf("") }
    var size by remember { mutableStateOf("") }
    var colour by remember { mutableStateOf("") }
    var newOnly by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf("new") }
    var open by remember { mutableStateOf<Product?>(null) }

    val cats = remember(products) { products.flatMap { catsOf(it) }.distinct().sorted() }
    val types = remember(products) { products.map { it.productType.trim() }.filter { it.isNotEmpty() }.distinct().sorted() }
    val brands = remember(products) { products.map { it.brand.trim() }.filter { it.isNotEmpty() }.distinct().sorted() }
    val colours = remember(products) { products.flatMap { p -> p.variants.map { it.colour } }.filter { it.isNotBlank() }.distinct().sorted() }
    val pool = remember(products) {
        products.flatMap { p -> listOf(p.article.ifBlank { p.name }, p.brand.trim(), p.productType.trim()) + catsOf(p) + p.variants.map { it.colour.trim() } }
            .filter { it.isNotBlank() }.distinct()
    }
    val sizes = remember(products, cat) {
        products.filter { cat == "All" || cat == "NEW" || inCat(it, cat) }
            .flatMap { p -> p.variants.flatMap { v -> v.sizes.map { it.size } } }.distinct().sortedBy(sizeOrder)
    }
    // MRP range: from the cheapest to the dearest product, in steps of 10
    val lo = remember(products) { products.minOfOrNull { catalogMrp(it) }?.let { floor(it / 10) * 10 } ?: 0.0 }
    val hi = remember(products) { products.maxOfOrNull { catalogMrp(it) }?.let { ceil(it / 10) * 10 } ?: 0.0 }
    var range by remember(lo, hi) { mutableStateOf(lo.toFloat()..hi.toFloat()) }

    fun clear() { query = ""; cat = "All"; type = ""; brand = ""; size = ""; colour = ""; newOnly = false; sort = "new"; range = lo.toFloat()..hi.toFloat() }

    val shown = products.filter { p ->
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val text = (listOf(p.name, p.article, p.brand, p.productType, p.category) + p.variants.map { it.colour }).joinToString(" ").lowercase()
        val textN = normQ(text)
        (cat == "All" || cat == "NEW" || inCat(p, cat)) &&
            (!(cat == "NEW" || newOnly) || p.isNewNow) &&
            (brand.isEmpty() || p.brand.trim() == brand) &&
            (type.isEmpty() || p.productType.trim() == type) &&
            (catalogMrp(p) >= range.start - 0.001 && catalogMrp(p) <= range.endInclusive + 0.001) &&
            (colour.isEmpty() || p.variants.any { it.colour == colour }) &&
            (size.isEmpty() || p.variants.any { v -> v.sizes.any { it.size == size && it.qty > 0 } }) &&
            words.all { w -> text.contains(w) || (normQ(w).isNotEmpty() && textN.contains(normQ(w))) }
    }.let { list ->
        when (sort) {
            "low" -> list.sortedBy { catalogMrp(it) }
            "high" -> list.sortedByDescending { catalogMrp(it) }
            else -> list.sortedByDescending { it.isNewNow }
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(158.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SearchWithSuggestions(query, { query = it }, pool)
                FilterRow("Category") {
                    Chip("All", cat == "All") { cat = "All" }
                    Chip("New", cat == "NEW") { cat = "NEW" }
                    cats.forEach { c -> Chip(c, cat == c) { cat = c } }
                }
                if (types.isNotEmpty()) FilterRow("Product type") {
                    Chip("All", type.isEmpty()) { type = "" }
                    types.forEach { t -> Chip(t, type == t) { type = t } }
                }
                if (sizes.isNotEmpty()) FilterRow("Size") {
                    Chip("All sizes", size.isEmpty()) { size = "" }
                    sizes.forEach { s -> Chip(s, size == s) { size = s } }
                }
                if (brands.isNotEmpty()) FilterRow("Brand") {
                    Chip("All brands", brand.isEmpty()) { brand = "" }
                    brands.forEach { b -> Chip(b, brand == b) { brand = b } }
                }
                if (colours.isNotEmpty()) FilterRow("Colour") {
                    Chip("All colours", colour.isEmpty()) { colour = "" }
                    colours.forEach { c ->
                        FilterChip(selected = colour == c, onClick = { colour = c }, label = { Text(c) },
                            leadingIcon = { ColourDot(c, 14) })
                    }
                }
                if (hi > lo) {
                    Text("MRP  ${money(app, range.start.toDouble())} – ${money(app, range.endInclusive.toDouble())}",
                        fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    RangeSlider(
                        value = range, onValueChange = { r -> range = (Math.round(r.start / 10f) * 10f)..(Math.round(r.endInclusive / 10f) * 10f) },
                        valueRange = lo.toFloat()..hi.toFloat()
                    )
                }
                FilterRow("Sort") {
                    Chip("New first", sort == "new") { sort = "new" }
                    Chip("MRP low to high", sort == "low") { sort = "low" }
                    Chip("MRP high to low", sort == "high") { sort = "high" }
                    Chip("New items only", newOnly) { newOnly = !newOnly }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${shown.size} product" + if (shown.size == 1) "" else "s", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { clear() }) { Text("Clear filters") }
                }
                if (shown.isEmpty()) EmptyState(if (products.isEmpty()) "No products on the website yet." else "No products match this filter.")
            }
        }
        items(shown, key = { it.id }) { p -> CatalogCard(app, p) { open = p } }
    }

    open?.let { p -> CatalogDetail(app, p) { open = null } }
}

/** Suggestions while typing (like the website): matching articles, brands, product types, categories and colours. */
private fun suggest(q: String, pool: List<String>): List<String> {
    val n = normQ(q)
    if (n.isEmpty()) return emptyList()
    return pool.filter { val p = normQ(it); p.contains(n) && p != n }
        .sortedWith(compareBy<String>({ !normQ(it).startsWith(n) }, { it.length })).take(6)
}

@Composable
private fun SearchWithSuggestions(query: String, onChange: (String) -> Unit, pool: List<String>) {
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val list = remember(query, pool) { suggest(query, pool) }
    Column {
        OutlinedTextField(
            query, onChange, singleLine = true,
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            placeholder = { Text("Search article, brand, colour, type") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Filled.Close, "Clear") } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() })
        )
        if (focused && list.isNotEmpty()) Card(
            Modifier.fillMaxWidth().padding(top = 2.dp), elevation = CardDefaults.cardElevation(4.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            list.forEach { s ->
                Row(
                    Modifier.fillMaxWidth().clickable { onChange(s); focus.clearFocus() }.padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Search, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(s, modifier = Modifier.padding(start = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun FilterRow(label: String, content: @Composable RowScope.() -> Unit) {
    Column {
        Text(label.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

@Composable
private fun ColourDot(name: String, sizeDp: Int) {
    Box(Modifier.size(sizeDp.dp).clip(CircleShape).background(colourOf(name)).border(1.dp, Color(0x33000000), CircleShape))
}

/** A product photo that fills the width (square). Private B2 photos get a signed link first. */
@Composable
private fun CatalogPhoto(src: String?, modifier: Modifier) {
    val isPrivate = src != null && src.startsWith("products/")
    val ctx = LocalContext.current
    val remoteUrl by produceState<Any?>(null, src) {
        value = if (src != null && isPrivate) (PhotoCache.local(ctx, src) ?: PhotoCache.ensure(ctx, src) ?: privatePhotoUrl(src)) else null
    }
    val model: Any? = when {
        src == null -> null
        isPrivate -> remoteUrl
        else -> imgModel(src)
    }
    if (model == null) {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        AsyncImage(model = model, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
    }
}

@Composable
private fun CatalogCard(app: App, p: Product, onOpen: () -> Unit) {
    val v = p.variants.firstOrNull { va -> va.sizes.any { it.qty > 0 } } ?: p.variants.firstOrNull()
    val photo = (v?.images ?: emptyList()).firstOrNull() ?: p.allImages.firstOrNull()
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onOpen() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Box {
            CatalogPhoto(photo, Modifier.fillMaxWidth().aspectRatio(1f))
            if (p.isNewNow) Text("NEW", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(8.dp).clip(RoundedCornerShape(6.dp)).background(BrandRed).padding(horizontal = 6.dp, vertical = 2.dp))
        }
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (p.brand.isNotBlank()) Text(p.brand.uppercase(), fontSize = 10.sp, letterSpacing = 0.6.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(p.article.ifBlank { p.name }, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val cols = p.variants.map { it.colour }.filter { it.isNotBlank() }
            if (cols.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                cols.take(6).forEach { ColourDot(it, 12) }
                if (cols.size > 6) Text("+${cols.size - 6}", fontSize = 10.sp)
            }
            Text("MRP ${money(app, catalogMrp(p))}", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
            val sz = (v?.sizes ?: emptyList()).sortedBy { sizeOrder(it.size) }
            if (sz.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                sz.forEach { s -> SizePill(s.size, s.qty > 0, small = true) }
            }
        }
    }
}

@Composable
private fun SizePill(size: String, inStock: Boolean, small: Boolean) {
    Text(
        size, fontSize = if (small) 11.sp else 14.sp, fontWeight = FontWeight.SemiBold,
        color = if (inStock) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
        textDecoration = if (inStock) null else TextDecoration.LineThrough,
        modifier = Modifier.border(1.dp, if (inStock) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant,
            RoundedCornerShape(6.dp)).padding(horizontal = if (small) 6.dp else 10.dp, vertical = if (small) 1.dp else 4.dp)
    )
}

/** One product: photos of the chosen colour, colours, sizes (crossed out = not in stock) and the MRP. */
@Composable
private fun CatalogDetail(app: App, p: Product, onClose: () -> Unit) {
    val first = p.variants.indexOfFirst { va -> va.sizes.any { it.qty > 0 } }.coerceAtLeast(0)
    var vi by remember(p.id) { mutableStateOf(first) }
    val v = p.variants.getOrNull(vi)
    val photos = ((v?.images ?: emptyList()) + p.images).distinct().ifEmpty { listOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        title = {
            Column {
                if (p.brand.isNotBlank()) Text(p.brand.uppercase(), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(p.article.ifBlank { p.name }, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(photos) { src -> CatalogPhoto(src, Modifier.size(230.dp).clip(RoundedCornerShape(12.dp))) }
                }
                Text("MRP ${money(app, catalogMrp(p))}", fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
                if (p.productType.isNotBlank() || p.category.isNotBlank()) Text(
                    listOf(p.productType, catsOf(p).joinToString(", ")).filter { it.isNotBlank() }.joinToString(" · "),
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (p.variants.size > 1 || (v?.colour ?: "").isNotBlank()) {
                    Text("Colour", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        p.variants.forEachIndexed { i, va ->
                            FilterChip(selected = i == vi, onClick = { vi = i }, label = { Text(va.colour.ifBlank { "-" }) },
                                leadingIcon = { ColourDot(va.colour, 14) })
                        }
                    }
                }
                val sz = (v?.sizes ?: emptyList()).sortedBy { sizeOrder(it.size) }
                if (sz.isNotEmpty()) {
                    Text("Sizes", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        sz.forEach { s -> SizePill(s.size, s.qty > 0, small = false) }
                    }
                }
                if (p.description.isNotBlank()) Text(p.description, fontSize = 13.sp)
            }
        }
    )
}
