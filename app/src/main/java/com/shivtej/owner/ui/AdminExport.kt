package com.shivtej.owner.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.shivtej.owner.data.CustomerE
import com.shivtej.owner.data.OrderE
import com.shivtej.owner.data.PosCustomerE
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Admin Portal exports: plain CSV files (Excel and Google Sheets open them). Nothing in the data is changed. */

private fun csvCell(v: Any?): String {
    val s = v?.toString() ?: ""
    return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}

private fun csvLine(vararg cells: Any?): String = cells.joinToString(",") { csvCell(it) } + "\r\n"

fun ordersCsv(orders: List<OrderE>): String = buildString {
    append("﻿")   // byte-order mark: Excel shows Hindi names correctly
    append(csvLine("Order ID", "Date", "Name", "Mobile", "Items", "Subtotal", "Shipping", "Total",
        "Payment", "Order status", "Tracking no", "Courier"))
    val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US)
    orders.sortedByDescending { it.createdAt }.forEach { o ->
        val items = try { JSONArray(o.itemsJson).length() } catch (e: Exception) { 0 }
        append(csvLine(o.id, fmt.format(Date(o.createdAt)), custField(o.customerJson, "name"),
            custField(o.customerJson, "mobile", "phone"), items, o.subtotal, o.shipping, o.total,
            o.paymentStatus, o.orderStatus, o.trackingNo, o.courier))
    }
}

fun shopCustomersCsv(list: List<PosCustomerE>): String = buildString {
    append("﻿")
    append(csvLine("Name", "Mobile", "Address"))
    list.sortedBy { it.name.lowercase() }.forEach { append(csvLine(it.name, it.mobile, it.address)) }
}

fun webCustomersCsv(list: List<CustomerE>): String = buildString {
    append("﻿")
    append(csvLine("Name", "Mobile", "Email"))
    list.sortedBy { it.name.lowercase() }.forEach { append(csvLine(it.name, it.mobile, it.email)) }
}

/** Writes the CSV into the app's export folder and opens the share sheet. */
fun shareCsv(ctx: Context, fileName: String, body: String) {
    val dir = File(ctx.filesDir, "exports").apply { mkdirs() }
    val f = File(dir, fileName)
    f.writeText(body, Charsets.UTF_8)
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    val i = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(i, "Export").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
