@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.shivtej.staff

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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Logout
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.floor

/*
 * The catalog: the website's products (switched on, with stock), the website's filters
 * (search, category, product type, size, brand, colour, MRP range, new only, sort). Only the MRP is shown.
 * Nothing can be changed here.
 */

private val KIDS_CATS = listOf("Kids", "Boy", "Girl")
private fun inCat(p: SProduct, cat: String): Boolean = if (cat == "Kids") p.cats.any { it in KIDS_CATS } else cat in p.cats
private fun normQ(s: String): String = s.lowercase().replace(Regex("[^a-z0-9\\u0900-\\u097F]+"), "")
private val sizeOrder: (String) -> Double = { it.toDoubleOrNull() ?: 9999.0 }

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

@Composable
fun CatalogScreen(store: Store, staffName: String, onMakeBill: () -> Unit, onClosed: () -> Unit) {
    val scope = rememberCoroutineScope()
    var products by remember { mutableStateOf<List<SProduct>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    fun load() {
        val t = store.token ?: return onClosed()
        loading = true; loadError = null
        scope.launch {
            try {
                products = withContext(Dispatchers.IO) { parseCatalog(Api.catalog(t, store.deviceId)) }
            } catch (e: NotAllowed) {
                onClosed()
            } catch (e: Exception) {
                loadError = "Catalog नहीं खुला। ऊपर ⟳ दबाएँ।\n(${(e.message ?: "unknown").take(140)})"
            } finally { loading = false }
        }
    }
    LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxSize().background(PanelBg)) {
        var menu by remember { mutableStateOf(false) }
        PremiumHeader(
            title = "Shivtej Managers",
            subtitle = staffName.ifBlank { "दुकान कैटलॉग" },
            actions = {
                IconButton(onClick = { load() }, enabled = !loading) { Icon(Icons.Filled.Refresh, "Refresh", tint = Color.White) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Menu", tint = Color.White) }
                    MenuSheet(expanded = menu, onDismiss = { menu = false },
                        onCatalog = { menu = false },
                        onMakeBill = { menu = false; onMakeBill() },
                        onLogout = { menu = false; onClosed() })
                }
            }
        )
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        val list = products
        when {
            list != null -> CatalogGrid(list)
            loadError != null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(loadError ?: "", color = MaterialTheme.colorScheme.error)
            }
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
    }
}

/** The premium three-dot menu: larger items with an icon badge, a title and a one-line hint. Make Bill is highlighted. */
@Composable
private fun MenuSheet(
    expanded: Boolean, onDismiss: () -> Unit,
    onCatalog: () -> Unit, onMakeBill: () -> Unit, onLogout: () -> Unit
) {
    DropdownMenu(
        expanded = expanded, onDismissRequest = onDismiss,
        modifier = Modifier.background(Color.White).width(248.dp)
    ) {
        MenuLine("Catalog", "सभी प्रोडक्ट देखें", Icons.Filled.MenuBook, StaffBlue, onCatalog)
        HorizontalDivider()
        MenuLine("Make Bill", "बिल बनाकर मालिक को भेजें", Icons.Filled.ReceiptLong, BrandRed, onMakeBill, highlight = true)
        HorizontalDivider()
        MenuLine("Logout", "ऐप से बाहर निकलें", Icons.Filled.Logout, Color(0xFF6B7280), onLogout)
    }
}

@Composable
private fun MenuLine(
    title: String, sub: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color, onClick: () -> Unit, highlight: Boolean = false
) {
    DropdownMenuItem(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        modifier = if (highlight) Modifier.background(Color(0xFFFDEEEE)) else Modifier,
        leadingIcon = {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(tint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) { Icon(icon, null, tint = tint) }
        },
        text = {
            Column {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Ink)
                Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}

@Composable
private fun CatalogGrid(products: List<SProduct>) {
    var query by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf("All") }
    var type by remember { mutableStateOf("") }
    var brand by remember { mutableStateOf("") }
    var size by remember { mutableStateOf("") }
    var colour by remember { mutableStateOf("") }
    var newOnly by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf("new") }
    var open by remember { mutableStateOf<SProduct?>(null) }

    val cats = remember(products) { products.flatMap { it.cats }.distinct().sorted() }
    val types = remember(products) { products.map { it.type }.filter { it.isNotEmpty() }.distinct().sorted() }
    val brands = remember(products) { products.map { it.brand }.filter { it.isNotEmpty() }.distinct().sorted() }
    val colours = remember(products) { products.flatMap { p -> p.colours.map { it.colour } }.filter { it.isNotBlank() }.distinct().sorted() }
    val pool = remember(products) {
        products.flatMap { p -> listOf(p.title, p.brand, p.type) + p.cats + p.colours.map { it.colour } }
            .filter { it.isNotBlank() }.distinct()
    }
    val sizes = remember(products, cat) {
        products.filter { cat == "All" || cat == "NEW" || inCat(it, cat) }
            .flatMap { p -> p.colours.flatMap { c -> c.sizes.map { it.size } } }.distinct().sortedBy(sizeOrder)
    }
    val lo = remember(products) { products.minOfOrNull { it.mrp }?.let { floor(it / 10) * 10 } ?: 0.0 }
    val hi = remember(products) { products.maxOfOrNull { it.mrp }?.let { ceil(it / 10) * 10 } ?: 0.0 }
    var range by remember(lo, hi) { mutableStateOf(lo.toFloat()..hi.toFloat()) }

    fun clear() { query = ""; cat = "All"; type = ""; brand = ""; size = ""; colour = ""; newOnly = false; sort = "new"; range = lo.toFloat()..hi.toFloat() }

    val shown = products.filter { p ->
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val text = (listOf(p.name, p.article, p.brand, p.type, p.category) + p.colours.map { it.colour }).joinToString(" ").lowercase()
        val textN = normQ(text)
        (cat == "All" || cat == "NEW" || inCat(p, cat)) &&
            (!(cat == "NEW" || newOnly) || p.isNew) &&
            (brand.isEmpty() || p.brand == brand) &&
            (type.isEmpty() || p.type == type) &&
            (p.mrp >= range.start - 0.001 && p.mrp <= range.endInclusive + 0.001) &&
            (colour.isEmpty() || p.colours.any { it.colour == colour }) &&
            (size.isEmpty() || p.colours.any { c -> c.sizes.any { it.size == size && it.inStock } }) &&
            words.all { w -> text.contains(w) || (normQ(w).isNotEmpty() && textN.contains(normQ(w))) }
    }.let { l ->
        when (sort) {
            "low" -> l.sortedBy { it.mrp }
            "high" -> l.sortedByDescending { it.mrp }
            else -> l.sortedByDescending { it.isNew }
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
                    Pick("All", cat == "All") { cat = "All" }
                    Pick("New", cat == "NEW") { cat = "NEW" }
                    cats.forEach { c -> Pick(c, cat == c) { cat = c } }
                }
                if (types.isNotEmpty()) FilterRow("Product type") {
                    Pick("All", type.isEmpty()) { type = "" }
                    types.forEach { t -> Pick(t, type == t) { type = t } }
                }
                if (sizes.isNotEmpty()) FilterRow("Size") {
                    Pick("All sizes", size.isEmpty()) { size = "" }
                    sizes.forEach { s -> Pick(s, size == s) { size = s } }
                }
                if (brands.isNotEmpty()) FilterRow("Brand") {
                    Pick("All brands", brand.isEmpty()) { brand = "" }
                    brands.forEach { b -> Pick(b, brand == b) { brand = b } }
                }
                if (colours.isNotEmpty()) FilterRow("Colour") {
                    Pick("All colours", colour.isEmpty()) { colour = "" }
                    colours.forEach { c ->
                        FilterChip(selected = colour == c, onClick = { colour = c }, label = { Text(c) }, leadingIcon = { ColourDot(c, 14) })
                    }
                }
                if (hi > lo) {
                    Text("MRP  ${money(range.start.toDouble())} – ${money(range.endInclusive.toDouble())}",
                        fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    RangeSlider(
                        value = range,
                        onValueChange = { r -> range = (Math.round(r.start / 10f) * 10f)..(Math.round(r.endInclusive / 10f) * 10f) },
                        valueRange = lo.toFloat()..hi.toFloat()
                    )
                }
                FilterRow("Sort") {
                    Pick("New first", sort == "new") { sort = "new" }
                    Pick("MRP low to high", sort == "low") { sort = "low" }
                    Pick("MRP high to low", sort == "high") { sort = "high" }
                    Pick("New items only", newOnly) { newOnly = !newOnly }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${shown.size} product" + if (shown.size == 1) "" else "s", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { clear() }) { Text("Clear filters") }
                }
                if (shown.isEmpty()) Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(if (products.isEmpty()) "Catalog में अभी कोई प्रोडक्ट नहीं है।" else "इस फ़िल्टर में कोई प्रोडक्ट नहीं।",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(shown, key = { it.id }) { p -> CatalogCard(p) { open = p } }
    }
    open?.let { p -> CatalogDetail(p) { open = null } }
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
private fun Pick(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
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

/** A product photo. Photos in the private B2 bucket get a signed link first. */
@Composable
private fun Photo(productId: String, src: String?, modifier: Modifier) {
    val model by produceState<String?>(null, src) {
        value = when {
            src == null -> null
            src.startsWith("products/") -> withContext(Dispatchers.IO) { Api.photoUrl(productId, src) }
            src.startsWith("http") -> src
            else -> null
        }
    }
    if (model == null) {
        Box(modifier.background(Color(0xFFF1F1F1)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Image, null, tint = Color(0xFFB0B0B0))
        }
    } else {
        AsyncImage(model = model, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
    }
}

@Composable
private fun CatalogCard(p: SProduct, onOpen: () -> Unit) {
    val c = p.colours.firstOrNull { col -> col.sizes.any { it.inStock } } ?: p.colours.firstOrNull()
    val photo = (c?.images ?: emptyList()).firstOrNull() ?: (p.images + p.colours.flatMap { it.images }).firstOrNull()
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onOpen() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(3.dp)
    ) {
        Box {
            Photo(p.id, photo, Modifier.fillMaxWidth().aspectRatio(1f))
            if (p.isNew) Text("NEW", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(8.dp).clip(RoundedCornerShape(6.dp)).background(BrandRed).padding(horizontal = 6.dp, vertical = 2.dp))
        }
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (p.brand.isNotBlank()) Text(p.brand.uppercase(), fontSize = 10.sp, letterSpacing = 0.6.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(p.title, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val cols = p.colours.map { it.colour }.filter { it.isNotBlank() }
            if (cols.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                cols.take(6).forEach { ColourDot(it, 12) }
                if (cols.size > 6) Text("+${cols.size - 6}", fontSize = 10.sp)
            }
            Text("MRP ${money(p.mrp)}", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
            val sz = (c?.sizes ?: emptyList()).sortedBy { sizeOrder(it.size) }
            if (sz.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                sz.forEach { s -> SizePill(s.size, s.inStock, small = true) }
            }
        }
    }
}

@Composable
private fun SizePill(size: String, inStock: Boolean, small: Boolean) {
    Text(
        size, fontSize = if (small) 11.sp else 14.sp, fontWeight = FontWeight.SemiBold,
        color = if (inStock) Color(0xFF111111) else Color(0xFFAAAAAA),
        textDecoration = if (inStock) null else TextDecoration.LineThrough,
        modifier = Modifier.border(1.dp, if (inStock) Color(0xFF888888) else Color(0xFFDDDDDD), RoundedCornerShape(6.dp))
            .padding(horizontal = if (small) 6.dp else 10.dp, vertical = if (small) 1.dp else 4.dp)
    )
}

/** One product: photos of the chosen colour, colours, sizes (crossed out = not in stock) and the MRP. */
@Composable
private fun CatalogDetail(p: SProduct, onClose: () -> Unit) {
    val first = p.colours.indexOfFirst { col -> col.sizes.any { it.inStock } }.coerceAtLeast(0)
    var ci by remember(p.id) { mutableStateOf(first) }
    val c = p.colours.getOrNull(ci)
    val photos: List<String?> = ((c?.images ?: emptyList()) + p.images).distinct().ifEmpty { listOf(null) }
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        title = {
            Column {
                if (p.brand.isNotBlank()) Text(p.brand.uppercase(), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(p.title, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(photos) { src -> Photo(p.id, src, Modifier.size(230.dp).clip(RoundedCornerShape(12.dp))) }
                }
                Text("MRP ${money(p.mrp)}", fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
                val info = listOf(p.type, p.cats.joinToString(", ")).filter { it.isNotBlank() }.joinToString(" · ")
                if (info.isNotBlank()) Text(info, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (p.colours.isNotEmpty()) {
                    Text("Colour", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        p.colours.forEachIndexed { i, col ->
                            FilterChip(selected = i == ci, onClick = { ci = i }, label = { Text(col.colour.ifBlank { "-" }) },
                                leadingIcon = { ColourDot(col.colour, 14) })
                        }
                    }
                }
                val sz = (c?.sizes ?: emptyList()).sortedBy { sizeOrder(it.size) }
                if (sz.isNotEmpty()) {
                    Text("Sizes", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        sz.forEach { s -> SizePill(s.size, s.inStock, small = false) }
                    }
                }
                if (p.description.isNotBlank()) Text(p.description, fontSize = 13.sp)
            }
        }
    )
}
