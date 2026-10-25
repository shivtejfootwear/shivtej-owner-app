package com.shivtej.owner.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
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

/** The MRP text of a size row: empty when the size uses the product MRP. */
internal fun mrpText(d: Double): String = if (d > 0) moneyText(d) else ""

/** The sizes of a group as text, e.g. "5, 6, 7, 8, 9". */
private fun sizesText(p: SizePreset): String = p.sizes.joinToString(", ")

private fun kindTitle(kind: String): String = when (kind) {
    SizePresets.SMALL -> "Small size"
    SizePresets.BIG -> "Big size"
    else -> "Custom sizes"
}

/**
 * Adds a saved group to one colour. A size that is already on the colour keeps its stock; it only takes the group's MRP.
 */
internal fun applySizeGroup(v: VariantDraft, p: SizePreset) {
    val mrp = mrpText(p.mrp)
    p.sizes.forEach { sz ->
        val have = v.sizes.firstOrNull { it.size.trim() == sz }
        if (have == null) v.sizes.add(SizeDraft(sz, "0", emptyList(), mrp))
        else if (mrp.isNotEmpty()) have.mrp = mrp
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
 * The dashboard of saved size groups for one kind ("+ Custom", "Small size" or "Big size").
 * Tap a group to add its sizes (and its MRP) to this colour. Add, edit or delete groups here.
 * Every change is saved on this phone and sent to the server.
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
                                val mrpPart = if (p.mrp > 0) "MRP ₹${moneyText(p.mrp)}" else "MRP: product MRP"
                                val sub = if (p.label.isBlank()) mrpPart else "${sizesText(p)}  ·  $mrpPart"
                                Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/** Add or edit one group: an optional name, the sizes (5-9 or 5, 6, 7) and an optional MRP for those sizes. */
@Composable
private fun SizeGroupEditor(kind: String, initial: SizePreset?, onSave: (SizePreset) -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf(initial?.label ?: "") }
    var sizes by remember { mutableStateOf(initial?.let { sizesText(it) } ?: "") }
    var mrp by remember { mutableStateOf(initial?.let { mrpText(it.mrp) } ?: "") }
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
                OutlinedTextField(mrp, { mrp = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("MRP for these sizes (optional)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                Text("Leave the MRP empty to use the product MRP.", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val list = SizePresets.parseSizes(sizes)
                val m: Double? = if (mrp.isBlank()) 0.0 else mrp.toDoubleOrNull()
                when {
                    list == null -> error = "Type sizes like 5-9 or 5, 6, 7 (numbers only)."
                    m == null -> error = "The MRP must be a number."
                    else -> onSave(SizePreset(initial?.id ?: UUID.randomUUID().toString(), kind, name.trim(), list, m))
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}
