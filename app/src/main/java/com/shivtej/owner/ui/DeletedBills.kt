package com.shivtej.owner.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shivtej.owner.App
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * History of every deleted bill, newest deletion first. Nothing in the app erases these rows: a deleted bill
 * can only be brought back ("रिस्टोर") from its receipt. Tap a row to open its receipt.
 */
@Composable
fun DeletedBillsScreen(app: App, nav: Nav) {
    val rows by app.repo.deletedBills.collectAsStateWithLifecycle(emptyList())
    val fmt = remember { SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                "यहाँ वो सब बिल हैं जो डिलीट हुए हैं। इन्हें यहाँ से मिटाया नहीं जा सकता। " +
                    "किसी बिल को फिर चालू करने के लिए उसे खोलें और \"रिस्टोर\" दबाएँ।",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (rows.isEmpty()) item { EmptyState("अभी तक कोई बिल डिलीट नहीं हुआ।") }
        items(rows, key = { it.id }) { b ->
            Card(
                Modifier.fillMaxWidth().clickable { nav.go(Route.Receipt(b.id)) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.DeleteForever, null, tint = RedOut, modifier = Modifier.size(22.dp))
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(b.billNo, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(
                            b.customerName.ifBlank { "Walk-in Customer" } + " · बिल बना: " + fmt.format(Date(b.createdAt)),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2
                        )
                        Text(
                            "डिलीट: " + (if (b.deletedAt > 0L) fmt.format(Date(b.deletedAt)) else "-"),
                            fontSize = 12.sp, color = RedOut
                        )
                    }
                    Text(money(app, b.total), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }
    }
}
