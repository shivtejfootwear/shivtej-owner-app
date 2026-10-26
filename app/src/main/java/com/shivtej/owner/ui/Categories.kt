@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.shivtej.owner.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.shivtej.owner.App
import com.shivtej.owner.data.CatType
import com.shivtej.owner.data.Product
import com.shivtej.owner.data.capName
import kotlinx.coroutines.launch

/** What the category box is asking the owner at the moment. */
private sealed interface CatDlg {
    object NewType : CatDlg
    data class RenameType(val name: String) : CatDlg
    data class DeleteType(val name: String) : CatDlg
    data class NewCat(val type: String) : CatDlg
    data class RenameCat(val type: String, val cat: String) : CatDlg
    data class DeleteCat(val type: String, val cat: String) : CatDlg
    object SetCategory : CatDlg
}

/**
 * CATEGORY box (opened from the Products screen): product types, the categories under each type,
 * and the products that are not in a category yet (tap to select, then SET CATEGORY).
 * Every name is saved in capital letters. Deleting never removes a product: it only clears its type / category.
 */
@Composable
fun CategoryDialog(app: App, products: List<Product>, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val tree = remember { mutableStateListOf<CatType>().also { it.addAll(app.repo.categoryTree()) } }
    val expanded = remember { mutableStateListOf<String>() }
    val selected = remember { mutableStateListOf<String>() }
    var dlg by remember { mutableStateOf<CatDlg?>(null) }

    fun saveTree() { scope.launch { app.repo.saveCategoryTree(tree.toList()) } }

    fun saveAll(list: List<Product>, after: () -> Unit = {}) {
        if (list.isEmpty()) return
        scope.launch {
            var err: String? = null
            for (p in list) {
                try { app.repo.saveProduct(p, false) } catch (e: Exception) { err = e.message ?: "Error"; break }
            }
            toast(ctx, err ?: "${list.size} प्रोडक्ट अपडेट हुए")
            after()
        }
    }

    val notIn = products.filter { it.subCategory.isBlank() }.sortedBy { it.article }
    val picked = products.filter { it.id in selected }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.List, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("CATEGORY", fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Close") }
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { SectionTitle("PRODUCT TYPES") }
                    items(tree.toList(), key = { it.name }) { t ->
                        val open = t.name in expanded
                        val typeCount = products.count { it.productType == t.name }
                        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                            Column(Modifier.padding(10.dp)) {
                                Row(Modifier.fillMaxWidth().clickable { if (open) expanded.remove(t.name) else expanded.add(t.name) },
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null)
                                    Column(Modifier.weight(1f).padding(start = 6.dp)) {
                                        Text(t.name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                        Text("${t.cats.size} categories · $typeCount products", fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    IconButton(onClick = { dlg = CatDlg.RenameType(t.name) }) { Icon(Icons.Filled.Edit, "Rename type") }
                                    IconButton(onClick = { dlg = CatDlg.DeleteType(t.name) }) { Icon(Icons.Filled.Delete, "Delete type") }
                                }
                                if (open) {
                                    t.cats.forEach { c ->
                                        val n = products.count { it.productType == t.name && it.subCategory == c }
                                        Row(Modifier.fillMaxWidth().padding(start = 30.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Text(c, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                            Text("$n", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            IconButton(onClick = { dlg = CatDlg.RenameCat(t.name, c) }) { Icon(Icons.Filled.Edit, "Rename category") }
                                            IconButton(onClick = { dlg = CatDlg.DeleteCat(t.name, c) }) { Icon(Icons.Filled.Delete, "Delete category") }
                                        }
                                    }
                                    TextButton(onClick = { dlg = CatDlg.NewCat(t.name) }, modifier = Modifier.padding(start = 24.dp)) {
                                        Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("ADD CATEGORY")
                                    }
                                }
                            }
                        }
                    }
                    item {
                        OutlinedButton(onClick = { dlg = CatDlg.NewType }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                            Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("ADD PRODUCT TYPE")
                        }
                    }

                    item { SectionTitle("NOT IN A CATEGORY YET (${notIn.size})") }
                    if (notIn.isEmpty()) item {
                        Text("सभी प्रोडक्ट की कैटेगरी सेट है।", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    itemsIndexed(notIn, key = { _, p -> p.id }) { _, p ->
                        val on = p.id in selected
                        Row(Modifier.fillMaxWidth().clickable { if (on) selected.remove(p.id) else selected.add(p.id) }
                            .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = on, onCheckedChange = { if (it) selected.add(p.id) else selected.remove(p.id) })
                            Column(Modifier.weight(1f)) {
                                Text(p.article.ifBlank { p.name }, fontWeight = FontWeight.SemiBold)
                                Text(p.productType.ifBlank { "TYPE NOT SET" }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                Button(onClick = { dlg = CatDlg.SetCategory }, enabled = picked.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Filled.Done, null); Spacer(Modifier.width(8.dp))
                    Text("SET CATEGORY (${picked.size})", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    when (val d = dlg) {
        null -> {}
        CatDlg.NewType -> NameDialog("NEW PRODUCT TYPE", "Product type name", "", onDismiss = { dlg = null }) { n ->
            val name = capName(n)
            if (tree.none { it.name == name }) { tree.add(CatType(name, emptyList())); saveTree() }
            dlg = null
        }
        is CatDlg.RenameType -> NameDialog("RENAME PRODUCT TYPE", "Product type name", d.name, onDismiss = { dlg = null }) { n ->
            val name = capName(n)
            val i = tree.indexOfFirst { it.name == d.name }
            if (i >= 0 && name != d.name) {
                tree[i] = tree[i].copy(name = name); saveTree()
                saveAll(products.filter { it.productType == d.name }.map { it.copy(productType = name) })
            }
            dlg = null
        }
        is CatDlg.DeleteType -> ConfirmDialog(
            "Delete ${d.name}?",
            "${products.count { it.productType == d.name }} products will lose this type and category (they stay in the shop, only the type is cleared).",
            "DELETE", onConfirm = {
                tree.removeAll { it.name == d.name }; saveTree()
                saveAll(products.filter { it.productType == d.name }.map { it.copy(productType = "", subCategory = "") })
                dlg = null
            }, onDismiss = { dlg = null })
        is CatDlg.NewCat -> NameDialog("ADD CATEGORY", "Category name (e.g. V BELT)", "", onDismiss = { dlg = null }) { n ->
            val name = capName(n)
            val i = tree.indexOfFirst { it.name == d.type }
            if (i >= 0 && name.isNotEmpty() && name !in tree[i].cats) { tree[i] = tree[i].copy(cats = tree[i].cats + name); saveTree() }
            dlg = null
        }
        is CatDlg.RenameCat -> NameDialog("RENAME CATEGORY", "Category name", d.cat, onDismiss = { dlg = null }) { n ->
            val name = capName(n)
            val i = tree.indexOfFirst { it.name == d.type }
            if (i >= 0 && name != d.cat) {
                tree[i] = tree[i].copy(cats = tree[i].cats.map { if (it == d.cat) name else it }); saveTree()
                saveAll(products.filter { it.productType == d.type && it.subCategory == d.cat }.map { it.copy(subCategory = name) })
            }
            dlg = null
        }
        is CatDlg.DeleteCat -> ConfirmDialog(
            "Delete ${d.cat}?",
            "${products.count { it.productType == d.type && it.subCategory == d.cat }} products will move to 'not in a category'.",
            "DELETE", onConfirm = {
                val i = tree.indexOfFirst { it.name == d.type }
                if (i >= 0) { tree[i] = tree[i].copy(cats = tree[i].cats - d.cat); saveTree() }
                saveAll(products.filter { it.productType == d.type && it.subCategory == d.cat }.map { it.copy(subCategory = "") })
                dlg = null
            }, onDismiss = { dlg = null })
        CatDlg.SetCategory -> SetCategoryDialog(tree.toList(), picked, onDismiss = { dlg = null }) { type, cat ->
            val i = tree.indexOfFirst { it.name == type }
            if (i >= 0 && cat !in tree[i].cats) { tree[i] = tree[i].copy(cats = tree[i].cats + cat); saveTree() }
            saveAll(picked.map { it.copy(productType = type, subCategory = cat) }) { selected.clear() }
            dlg = null
        }
    }
}

/** A one-line name box. Typing is shown in capital letters. */
@Composable
private fun NameDialog(title: String, label: String, initial: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(v, { v = it.uppercase() }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(onClick = { if (v.isNotBlank()) onOk(v) }) { Text("SAVE") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL") } }
    )
}

/** Pick the product type and the category for the ticked products. Suggestions are the categories already saved for that type. */
@Composable
private fun SetCategoryDialog(tree: List<CatType>, picked: List<Product>, onDismiss: () -> Unit, onAssign: (String, String) -> Unit) {
    val first = picked.firstOrNull { p -> tree.any { it.name == p.productType } }?.productType
        ?: tree.firstOrNull()?.name ?: ""
    var type by remember { mutableStateOf(first) }
    var cat by remember { mutableStateOf("") }
    val cats = tree.firstOrNull { it.name == type }?.cats ?: emptyList()
    val shown = cats.filter { cat.isBlank() || it.contains(cat, ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("SET CATEGORY FOR ${picked.size} PRODUCT(S)", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("PRODUCT TYPE", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tree.forEach { t -> Chip(t.name, t.name == type) { type = t.name; cat = "" } }
                }
                OutlinedTextField(cat, { cat = it.uppercase() }, label = { Text("CATEGORY (type it or tap one)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    shown.forEach { c -> Chip(c, c == cat) { cat = c } }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (type.isNotBlank() && cat.isNotBlank()) onAssign(type, capName(cat)) }) { Text("ASSIGN") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL") } }
    )
}
