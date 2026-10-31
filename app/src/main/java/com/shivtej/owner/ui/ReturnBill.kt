package com.shivtej.owner.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shivtej.owner.App
import com.shivtej.owner.data.Repo
import com.shivtej.owner.util.BillMath
import kotlinx.coroutines.launch

/** Reasons the owner can pick for a return. */
val RETURN_REASONS = listOf("साइज़ छोटा", "साइज़ बड़ा", "खराब माल", "पसंद नहीं", "दूसरा रंग चाहिए")

private fun amountText(v: Double): String =
    if (v % 1.0 == 0.0) v.toLong().toString() else String.format(java.util.Locale.ENGLISH, "%.2f", v)

/**
 * A piece that was sold in a shop (POS) bill was scanned: the return bill.
 * Shows the bill and the price paid for this piece, asks the reason and the refund, then returns it:
 * the piece goes back to stock (+1) and the return is saved in the Returns list.
 * Only for barcodes sold at the counter. Online orders never reach this screen.
 */
@Composable
fun ReturnBillDialog(app: App, s: Repo.SoldInfo, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var reason by remember { mutableStateOf(RETURN_REASONS[0]) }
    var refundText by remember { mutableStateOf(amountText(s.price)) }
    var busy by remember { mutableStateOf(false) }
    var slip by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text(if (slip == null) "वापसी बिल" else "वापसी हो गई") },
        text = {
            val done = slip
            if (done == null) {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${s.article} · ${s.colour} · साइज़ ${s.size}", fontWeight = FontWeight.Bold)
                    Text("मूल बिल: ${s.bill} · इस पीस का भाव: ${money(app, s.price)}", fontSize = 13.sp)
                    Text("कारण चुनें", fontWeight = FontWeight.SemiBold)
                    RETURN_REASONS.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            pair.forEach { r ->
                                FilterChip(selected = reason == r, onClick = { reason = r }, label = { Text(r) })
                            }
                        }
                    }
                    OutlinedTextField(value = refundText,
                        onValueChange = { t -> refundText = t.filter { it.isDigit() || it == '.' } },
                        label = { Text("वापस की जाने वाली रकम (₹)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("रकम इस पीस के भाव से ज़्यादा नहीं हो सकती। जो नकद नहीं लौटेगी, वह ग्राहक के उधार में घटेगी। वापसी होते ही माल स्टॉक में +1 जुड़ेगा।",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Text(done)
            }
        },
        confirmButton = {
            val done = slip
            if (done == null) {
                TextButton(enabled = !busy, onClick = {
                    val amount = BillMath.parseAmount(refundText) ?: 0.0
                    busy = true
                    scope.launch {
                        val res = runCatching { app.repo.restockSold(s.code, reason, amount) }
                        busy = false
                        res.onSuccess { ok ->
                            if (ok) {
                                Beeps.ok(ctx)
                                slip = "वापसी बिल · ${s.article} ${s.colour} साइज़ ${s.size}\n" +
                                    "मूल बिल: ${s.bill}\n" +
                                    "वापस दी गई रकम: ${money(app, amount)}\n" +
                                    "कारण: $reason\n" +
                                    "स्टॉक में जुड़ा: +1"
                            } else {
                                toast(ctx, "स्टॉक में नहीं जुड़ पाया")
                            }
                        }.onFailure { toast(ctx, it.message ?: "वापसी नहीं हो पाई") }
                    }
                }) { Text("वापसी पक्की करें") }
            } else {
                TextButton(onClick = onClose) { Text("बंद करें") }
            }
        },
        dismissButton = {
            val done = slip
            if (done == null) {
                TextButton(enabled = !busy, onClick = onClose) { Text("रद्द करें") }
            } else {
                TextButton(onClick = {
                    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, done)
                    ctx.startActivity(Intent.createChooser(i, "रसीद भेजें"))
                }) { Text("शेयर करें") }
            }
        }
    )
}
