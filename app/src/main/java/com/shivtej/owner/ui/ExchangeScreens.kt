package com.shivtej.owner.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shivtej.owner.App
import com.shivtej.owner.data.BillMeta
import com.shivtej.owner.data.CartLine
import com.shivtej.owner.data.Repo
import com.shivtej.owner.util.BillMath
import com.shivtej.owner.util.Invoice
import com.shivtej.owner.util.Receipt
import com.shivtej.owner.util.ThermalText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ExBlue = Color(0xFF283593)

/**
 * A piece that is already on a shop bill was scanned in POS: three small icons.
 * Return = the piece comes back (return bill). Exchange = the piece comes back and new pieces go out (one exchange bill).
 */
@Composable
fun SoldPieceDialog(app: App, s: Repo.SoldInfo, onReturn: () -> Unit, onExchange: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("यह पीस पहले बिक चुका है", color = BrandRed, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${s.article} · ${s.colour} · साइज़ ${s.size}", fontWeight = FontWeight.Bold)
                Text("बिल ${s.bill} · ${money(app, s.price)}", fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallAction(Icons.AutoMirrored.Filled.Undo, "Return", BrandRed, Modifier.weight(1f), onReturn)
                    SmallAction(Icons.Filled.SwapHoriz, "Exchange", ExBlue, Modifier.weight(1f), onExchange)
                    SmallAction(Icons.Filled.Close, "Cancel", Color(0xFF757575), Modifier.weight(1f), onCancel)
                }
            }
        },
        confirmButton = {}
    )
}

@Composable
private fun SmallAction(icon: ImageVector, label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).border(2.dp, color, RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.06f)).clickable(onClick = onClick).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(26.dp))
        Text(label, color = color, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

/**
 * Exchange at the counter. Top: the piece that came back (its old bill rate). Then "scan the new piece":
 * each scanned piece is one line with its own RATE box. The difference is worked out at once.
 */
@Composable
fun ExchangeScreen(app: App, nav: Nav, code: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val withCamera = rememberWithCamera()
    val sold = remember(code) { app.repo.soldInfo(code) }
    val lines = remember { mutableStateListOf<CartLine>() }
    var query by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf(RETURN_REASONS[0]) }
    var method by remember { mutableStateOf("Cash") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val scanJob = remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    if (sold == null) { EmptyState("This piece is not on a bill."); return }

    fun addCode(raw: String) {
        val c = raw.trim()
        query = ""
        if (c.isEmpty()) return
        scope.launch {
            val h = app.repo.lookup(c)
            when {
                h == null -> {
                    Beeps.fail(ctx)
                    val s2 = app.repo.soldInfo(c)
                    toast(ctx, if (s2 != null) "यह पीस बिल ${s2.bill} में पहले ही बिक चुका है" else "यह बारकोड नहीं मिला")
                }
                lines.any { c in it.barcode.split(",") } -> { Beeps.again(ctx); toast(ctx, "This piece is already added") }
                else -> {
                    val same = lines.filter { it.productId == h.product.id && it.colour == h.colour && it.size == h.size.size }.sumOf { it.qty }
                    if (same + 1 > h.size.qty) { Beeps.fail(ctx); toast(ctx, "Only ${h.size.qty} in stock") }
                    else {
                        lines.add(CartLine(h.product.id, h.product.article, h.product.name, h.colour, h.size.size,
                            c, 1, maxOf(h.product.mrp, h.product.price), h.size.qty, h.product.mrp))
                        Beeps.ok(ctx)
                    }
                }
            }
            runCatching { focus.requestFocus() }
        }
    }

    val back = sold.price
    val newValue = BillMath.round2(lines.sumOf { it.price * it.qty })
    val diff = BillMath.round2(newValue - back)   // > 0 the customer pays, < 0 the shop gives back

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.Undo, null, tint = BrandRed)
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text("${sold.article} · ${sold.colour} · ${sold.size}", fontWeight = FontWeight.Bold)
                    Text("Returned · बिल ${sold.bill}", fontSize = 12.sp)
                }
                Text("− " + moneyG(app, back), fontWeight = FontWeight.Bold, color = BrandRed)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { v ->
                    query = v
                    scanJob.value?.cancel()
                    val t = v.trim()
                    if (isScannedCode(t)) scanJob.value = scope.launch { delay(500); addCode(t) }
                },
                singleLine = true,
                placeholder = { Text("नया पीस स्कैन करें") },
                leadingIcon = { IconButton(onClick = { withCamera { scanning = true } }) { Icon(Icons.Filled.QrCodeScanner, "Scan", tint = GreenOk) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { scanJob.value?.cancel(); addCode(query) }),
                modifier = Modifier.weight(1f).focusRequester(focus)
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick = { withCamera { scanning = true } }, modifier = Modifier.size(56.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = GreenOk)) { Icon(Icons.Filled.QrCodeScanner, "Scan") }
        }

        lines.forEachIndexed { i, l ->
            var rateText by remember(l.barcode) { mutableStateOf(BillMath.plain(l.price)) }
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("+ ${l.article} · ${l.colour} · ${l.size}", fontWeight = FontWeight.Bold)
                        Text("MRP ${moneyG(app, BillMath.lineMrp(l.mrp, l.price))} · ${l.barcode}", fontSize = 12.sp)
                    }
                    OutlinedTextField(
                        value = rateText,
                        onValueChange = { v ->
                            val f = v.filter { ch -> ch.isDigit() || ch == '.' }.take(9)
                            rateText = f
                            lines.getOrNull(i)?.let { cur -> lines[i] = cur.copy(price = f.toDoubleOrNull() ?: 0.0) }
                        },
                        label = { Text("RATE ₹", fontWeight = FontWeight.Bold) }, singleLine = true, modifier = Modifier.width(120.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = BrandRed, unfocusedBorderColor = BrandRed),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done)
                    )
                    IconButton(onClick = { lines.removeAt(i) }) { Icon(Icons.Filled.Delete, "Remove") }
                }
            }
        }

        Text("कारण", fontWeight = FontWeight.SemiBold)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RETURN_REASONS.forEach { r -> FilterChip(selected = reason == r, onClick = { reason = r }, label = { Text(r) }) }
        }
        Text("Payment", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Cash", "UPI", "Card").forEach { m -> FilterChip(selected = method == m, onClick = { method = m }, label = { Text(m) }) }
        }

        Card(Modifier.fillMaxWidth().border(2.dp, BrandRed, RoundedCornerShape(10.dp)), colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row { Text("नया माल", Modifier.weight(1f)); Text(moneyG(app, newValue)) }
                Row { Text("वापसी का क्रेडिट", Modifier.weight(1f)); Text("− " + moneyG(app, back), color = BrandRed) }
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (diff >= 0) "ग्राहक देगा" else "ग्राहक को वापस", Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(moneyG(app, kotlin.math.abs(diff)), fontWeight = FontWeight.Bold, fontSize = 24.sp,
                        color = if (diff >= 0) BrandRed else GreenOk)
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
        Button(
            enabled = !busy && lines.isNotEmpty() && lines.all { it.price > 0.0 },
            onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        val id = app.repo.exchangeSold(code, reason, method, lines.toList())
                        Beeps.ok(ctx)
                        nav.replace(Route.ReturnReceipt(id))
                    } catch (e: Exception) {
                        error = e.message ?: "Exchange नहीं हो पाया"
                    } finally { busy = false }
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = BrandRed),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text(if (busy) "Please wait..." else "Exchange बिल बनाएँ", fontWeight = FontWeight.Bold, fontSize = 17.sp) }
        Text("वापसी वाला पीस स्टॉक में +1, नया पीस स्टॉक से −1 — एक ही बार में।", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (scanning) ScannerDialog(onResult = { scanning = false; addCode(it) }, onDismiss = { scanning = false })
}

/** The bill of a return or an exchange (design A1): share as PDF, send on WhatsApp, print on the Bluetooth printer. */
@Composable
fun ReturnReceiptScreen(app: App, nav: Nav, returnId: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val withBt = rememberWithBluetooth(app)
    val r by produceState<com.shivtej.owner.data.ReturnE?>(null, returnId) { value = app.repo.returnsAll().firstOrNull { it.id == returnId } }
    val ret = r ?: run { EmptyState("Loading the bill..."); return }
    val old by produceState<com.shivtej.owner.data.BillE?>(null, ret.refId) { value = if (ret.refType == "BILL") app.repo.bill(ret.refId) else null }
    val pair by produceState<Pair<com.shivtej.owner.util.ReceiptData, android.graphics.Bitmap>?>(null, ret, old) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val (data, theme) = BillMeta.buildReturnReceipt(app.prefs, ret, old)
                data to Invoice.invoice(ctx, data, theme)
            }.getOrNull()
        }
    }
    val p = pair ?: run { EmptyState("Preparing the bill..."); return }
    val (data, bmp) = p
    var printMsg by remember { mutableStateOf<String?>(null) }
    val title = if (data.kind == "EXCHANGE") "Exchange" else "Return"
    val first = when {
        data.kind == "EXCHANGE" && ret.refund < 0.0 -> "$title bill ${data.billNo} - customer paid ${moneyG(app, -ret.refund)}."
        ret.refund > 0.0 -> "$title bill ${data.billNo} - refund ${moneyG(app, ret.refund)}."
        else -> "$title bill ${data.billNo}."
    } + " Thank you for shopping with " + app.prefs.storeName + "."
    val text = withBillNotes(first, data.shop.notes)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Image(bmp.asImageBitmap(), contentDescription = "Bill", contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { Receipt.share(ctx, Receipt.savePdf(ctx, bmp, data.billNo)) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Share, null); Spacer(Modifier.width(6.dp)); Text("Share PDF")
            }
            Button(onClick = {
                val mobile = BillMath.normMobile(data.customerMobile)
                val file = Receipt.savePdf(ctx, bmp, data.billNo)
                if (mobile.length == 10) {
                    Receipt.saveToDownloads(ctx, file)
                    if (!Receipt.whatsAppToCustomer(ctx, file, text, mobile)) toast(ctx, "WhatsApp नहीं खुला")
                } else Receipt.shareWhatsApp(ctx, file, text, mobile)
            }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366)), modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Chat, null); Spacer(Modifier.width(6.dp)); Text("WhatsApp")
            }
        }
        OutlinedButton(onClick = {
            withBt {
                scope.launch {
                    val res = try {
                        if (app.prefs.printMode == "text") app.printer.printText(ThermalText.build(data))
                        else {
                            val (_, theme) = BillMeta.buildReturnReceipt(app.prefs, ret, old)
                            val th = withContext(Dispatchers.Default) { Invoice.thermal(ctx, data, theme) }
                            app.printer.printBitmap(th)
                        }
                    } catch (e: Exception) { Result.failure<Unit>(e) }
                    printMsg = if (res.isSuccess) "Sent to printer." else (res.exceptionOrNull()?.message ?: "Could not print.")
                }
            }
        }, border = BorderStroke(1.dp, BrandRed), modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Print, null, tint = BrandRed); Spacer(Modifier.width(6.dp)); Text("Print Bill (Bluetooth)", color = BrandRed)
        }
        printMsg?.let { Text(it, fontSize = 13.sp) }
        OutlinedButton(onClick = { nav.replace(Route.Pos) }, modifier = Modifier.fillMaxWidth()) { Text("New bill") }
    }
}
