package com.shivtej.owner.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shivtej.owner.App
import com.shivtej.owner.data.PosCustomerE
import com.shivtej.owner.util.BillMath
import java.io.File

/**
 * Customer numbers: every customer with a mobile number, in one list.
 * "Share contacts" makes a .vcf file (opens in the phone's Contacts or Gmail import).
 * "Share CSV" makes a .csv file (name and mobile) that can be copied or imported in Gmail.
 */
@Composable
fun CustomerNumbersScreen(app: App) {
    val ctx = LocalContext.current
    val customers by app.repo.posCustomers.collectAsStateWithLifecycle(emptyList())
    var gender by remember { mutableStateOf("") }   // "" = all, "M" = male, "F" = female
    val all = remember(customers) {
        customers.mapNotNull { c ->
            val m = BillMath.normMobile(c.mobile)
            if (m.length == 10) c to m else null
        }.sortedBy { it.first.name.lowercase() }
    }
    val maleN = all.count { it.first.gender == "M" }
    val femaleN = all.count { it.first.gender == "F" }
    val rows = remember(all, gender) { if (gender.isBlank()) all else all.filter { it.first.gender == gender } }
    val tag = when (gender) { "M" -> "male"; "F" -> "female"; else -> "all" }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text("${all.size} customers with a mobile number. Share all, or only male / female customers (for offers and tasks).",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // who to share
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            FilterChip(selected = gender == "", onClick = { gender = "" }, label = { Text("All (${all.size})") }, modifier = Modifier.weight(1f))
            FilterChip(selected = gender == "M", onClick = { gender = "M" }, label = { Text("♂ Male ($maleN)") }, modifier = Modifier.weight(1f))
            FilterChip(selected = gender == "F", onClick = { gender = "F" }, label = { Text("♀ Female ($femaleN)") }, modifier = Modifier.weight(1f))
        }
        Text("Sharing ${rows.size} ${if (gender.isBlank()) "customers" else tag + " customers"}.", fontSize = 12.sp,
            fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Button(onClick = { shareFile(ctx, "customer-contacts-$tag.vcf", "text/vcard", vcard(rows)) },
                enabled = rows.isNotEmpty(), modifier = Modifier.weight(1f).height(50.dp)) {
                Icon(Icons.Filled.Share, null); Spacer(Modifier.width(4.dp)); Text("Contacts")
            }
            OutlinedButton(onClick = { shareFile(ctx, "customer-numbers-$tag.csv", "text/csv", csv(rows)) },
                enabled = rows.isNotEmpty(), modifier = Modifier.weight(1f).height(50.dp)) {
                Text("CSV")
            }
        }
        Text("Gender is set on the bill (MALE / FEMALE) or on the customer's page. Customers saved before this update show under \"All\" until you set it.",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
        rows.forEach { (c, m) ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(c.name.ifBlank { "Customer" }, fontWeight = FontWeight.SemiBold)
                    Text("+91 $m", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (rows.isEmpty()) Text("No customers with a mobile number yet.", fontSize = 13.sp)
        Spacer(Modifier.height(24.dp))
    }
}

private fun vcard(rows: List<Pair<PosCustomerE, String>>): String =
    rows.joinToString("") { (c, m) ->
        "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:${esc(c.name.ifBlank { "Customer" })}\r\nTEL;TYPE=CELL:+91$m\r\nEND:VCARD\r\n"
    }

private fun csv(rows: List<Pair<PosCustomerE, String>>): String =
    "Name,Mobile\n" + rows.joinToString("") { (c, m) ->
        "\"" + c.name.ifBlank { "Customer" }.replace("\"", "\"\"") + "\",+91$m\n"
    }

private fun esc(s: String): String =
    s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", " ")

private fun shareFile(ctx: Context, name: String, mime: String, body: String) {
    val dir = File(ctx.filesDir, "exports").apply { mkdirs() }
    val f = File(dir, name)
    f.writeText(body, Charsets.UTF_8)
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    val i = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(i, "Share customer numbers").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
