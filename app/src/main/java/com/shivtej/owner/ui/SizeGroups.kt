package com.shivtej.owner.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shivtej.owner.data.SizePreset
import com.shivtej.owner.data.SizePresets
import java.util.UUID

/** A price as the owner sees it: 2999 (not 2999.0). */
internal fun moneyText(d: Double): String = d.toBigDecimal().stripTrailingZeros().toPlainString()

/** A price text for a field: empty when the value is not set (0). */
internal fun mrpText(d: Double): String = if (d > 0) moneyText(d) else ""

private fun sizesText(p: SizePreset): String = p.sizes.joinToString(", ")

private fun kindTitle(kind: String): String = when (kind) {
    SizePresets.SMALL -> "Small size"
    SizePresets.BIG -> "Big size"
    else -> "Custom sizes"
}

/** "+ Custom": adds the sizes of a saved group to one colour. A size already there keeps its stock and price. */
internal fun applySizeGroup(v: VariantDraft, p: SizePreset) {
    p.sizes.forEach { sz ->
        if (v.sizes.none { it.size.trim() == sz }) v.sizes.add(SizeDraft(sz, "0"))
    }
}

/**
 * "+ Extra size": the sizes of one extra group get the group's MRP and selling price (once for the whole group).
 * A size already on the colour keeps its stock and barcodes; only its MRP and price change.
 */
internal fun applyExtraSizes(v: VariantDraft, p: SizePreset) {
    val mrp = mrpText(p.mrp)
    val price = mrpText(p.price)
    p.sizes.forEach { sz ->
        val have = v.sizes.firstOrNull { it.size.trim() == sz }
        if (have == null) v.sizes.add(SizeDraft(sz, "0", emptyList(), mrp, price))
        else {
            have.mrp = mrp
            have.price = price
        }
    }
}

/** "+ Extra size": the owner picks Small size or Big size. */
@Composable
internal fun SizeKindDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Extra size") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Which extra size?", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { onPick(SizePresets.SMALL) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text("Small size")
                }
                OutlinedButton(onClick = { onPick(SizePresets.BIG) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text("Big size")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * "+ Custom" dashboard: saved groups of sizes (no MRP, the product MRP and price are used).
 * Tap a group to add its sizes to this colour. Add, edit or delete groups here.
 */
@Composable
internal fun SizeGroupsDialog(
    kind: String,
    presets: List<SizePreset>,
    onPick: (SizePreset) -> Unit,
    onSaveAll: (List<SizePreset>) -> Unit,
    onDismiss: () -> Unit
) {
    var all by remember { mutableStateOf(presets) }
    var editing by remember { mutableStateOf<SizePreset?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SizePreset?>(null) }
    val mine = all.filter { it.kind == kind }

    fun save(list: List<SizePreset>) {
        all = list
        onSaveAll(list)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(kindTitle(kind)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Tap a group to add its sizes to this colour. Add, edit or delete groups here.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (mine.isEmpty()) Text("No saved groups yet. Tap Add new group.", fontSize = 14.sp)
                mine.forEach { p ->
                    Card(Modifier.fillMaxWidth().clickable { onPick(p) }) {
                        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(p.label.ifBlank { sizesText(p) }, fontWeight = FontWeight.SemiBold)
                                if (p.label.isNotBlank())
                                    Text(sizesText(p), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { editing = p }) { Icon(Icons.Filled.Edit, "Edit group") }
                            IconButton(onClick = { deleting = p }) { Icon(Icons.Filled.Delete, "Delete group") }
                        }
                    }
                }
                OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth().height(46.dp)) {
                    Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Add new group")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )

    if (adding || editing != null) {
        val start = editing
        SizeGroupEditor(
            kind = kind,
            initial = start,
            onSave = { p ->
                save(if (start == null) all + p else all.map { if (it.id == p.id) p else it })
                adding = false
                editing = null
            },
            onCancel = { adding = false; editing = null }
        )
    }
    deleting?.let { p ->
        ConfirmDialog("Delete group?",
            "\"${p.label.ifBlank { sizesText(p) }}\" will be removed from the list. Sizes already on a colour stay.",
            "Delete",
            { save(all.filter { it.id != p.id }); deleting = null },
            { deleting = null })
    }
}

/** Add or edit one "+ Custom" group: an optional name and the sizes (5-9 or 5, 6, 7). */
@Composable
private fun SizeGroupEditor(kind: String, initial: SizePreset?, onSave: (SizePreset) -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf(initial?.label ?: "") }
    var sizes by remember { mutableStateOf(initial?.let { sizesText(it) } ?: "") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (initial == null) "New group" else "Edit group") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name (optional, e.g. Ladies)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(sizes, { sizes = it }, label = { Text("Sizes (5-9 or 5, 6, 7)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val list = SizePresets.parseSizes(sizes)
                if (list == null) error = "Type sizes like 5-9 or 5, 6, 7 (numbers only)."
                else onSave(SizePreset(initial?.id ?: UUID.randomUUID().toString(), kind, name.trim(), list))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}

/**
 * "+ Extra size" (Small size / Big size): the MRP and selling price are typed ONCE for the whole group,
 * then the sizes of the group (for example 10 and 11). Tap a saved group to fill the form again.
 * "Add to this colour" adds the sizes with that MRP and price, and keeps the group for next time.
 */
@Composable
internal fun ExtraSizeDialog(
    kind: String,
    presets: List<SizePreset>,
    onApply: (SizePreset) -> Unit,
    onSaveAll: (List<SizePreset>) -> Unit,
    onDismiss: () -> Unit
) {
    var all by remember { mutableStateOf(presets) }
    var chosenId by remember { mutableStateOf<String?>(null) }
    var sizes by remember { mutableStateOf("") }
    var mrp by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<SizePreset?>(null) }
    val mine = all.filter { it.kind == kind }

    fun save(list: List<SizePreset>) {
        all = list
        onSaveAll(list)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(kindTitle(kind)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (mine.isNotEmpty()) {
                    Text("Saved groups (tap to fill in)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    mine.forEach { p ->
                        Row(Modifier.fillMaxWidth().clickable {
                            chosenId = p.id; sizes = sizesText(p); mrp = mrpText(p.mrp); price = mrpText(p.price); error = null
                        }, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(sizesText(p), fontWeight = FontWeight.SemiBold)
                                Text("MRP ₹${mrpText(p.mrp).ifBlank { "-" }}  ·  Selling ₹${mrpText(p.price).ifBlank { "-" }}",
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { deleting = p }) { Icon(Icons.Filled.Delete, "Delete group") }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
                OutlinedButton(onClick = {
                    chosenId = null; sizes = ""; mrp = ""; price = ""; error = null
                }, modifier = Modifier.fillMaxWidth()) { Text("New group") }
                OutlinedTextField(sizes, { sizes = it }, label = { Text("Sizes (1-4 or 10, 11)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(mrp, { mrp = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("MRP") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(price, { price = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Selling price *") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth())
                Text("This MRP and selling price go to every size in this group. Quantity and barcodes are added below, per size.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val list = SizePresets.parseSizes(sizes)
                val m: Double? = if (mrp.isBlank()) 0.0 else mrp.toDoubleOrNull()
                val pr: Double? = price.toDoubleOrNull()?.takeIf { it > 0 }
                when {
                    list == null -> error = "Type sizes like 1-4 or 10, 11 (numbers only)."
                    m == null -> error = "The MRP must be a number."
                    pr == null -> error = "Selling price is required (more than 0)."
                    else -> {
                        val id = chosenId ?: UUID.randomUUID().toString()
                        val p = SizePreset(id, kind, "", list, m, pr)
                        save(if (chosenId == null) all + p else all.map { if (it.id == id) p else it })
                        onApply(p)
                    }
                }
            }) { Text("Add to this colour") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )

    deleting?.let { p ->
        ConfirmDialog("Delete group?",
            "\"${sizesText(p)}\" will be removed from the list. Sizes already on a colour stay.",
            "Delete",
            {
                save(all.filter { it.id != p.id })
                if (chosenId == p.id) chosenId = null
                deleting = null
            },
            { deleting = null })
    }
}
