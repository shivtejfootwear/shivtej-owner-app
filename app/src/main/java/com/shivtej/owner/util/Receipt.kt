package com.shivtej.owner.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import androidx.print.PrintHelper
import com.shivtej.owner.data.BillE
import com.shivtej.owner.data.BillItem
import com.shivtej.owner.data.Prefs
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Receipt {
    private const val W = 576

    fun money(prefs: Prefs, v: Double): String =
        prefs.currency + (if (v % 1.0 == 0.0) v.toLong().toString() else String.format(Locale.US, "%.2f", v))

    fun render(prefs: Prefs, bill: BillE, items: List<BillItem>): Bitmap {
        val normal = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 26f }
        val bold = Paint(normal).apply { typeface = Typeface.DEFAULT_BOLD }
        val big = Paint(bold).apply { textSize = 38f; textAlign = Paint.Align.CENTER }
        val center = Paint(normal).apply { textAlign = Paint.Align.CENTER }
        val right = Paint(normal).apply { textAlign = Paint.Align.RIGHT }
        val rightBold = Paint(bold).apply { textAlign = Paint.Align.RIGHT }

        val height = 420 + items.size * 84
        val bmp = Bitmap.createBitmap(W, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        var y = 56f
        c.drawText(prefs.storeName, W / 2f, y, big); y += 36f
        if (prefs.storeAddress.isNotBlank()) { c.drawText(prefs.storeAddress, W / 2f, y, center); y += 32f }
        if (prefs.storePhone.isNotBlank()) { c.drawText("Ph: " + prefs.storePhone, W / 2f, y, center); y += 32f }
        y += 8f
        c.drawText("Bill No: ${bill.billNo}", 20f, y, bold); y += 34f
        c.drawText(SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(bill.createdAt)), 20f, y, normal); y += 20f
        c.drawLine(20f, y, W - 20f, y, normal); y += 34f
        for (it in items) {
            c.drawText(it.name.take(34), 20f, y, bold); y += 30f
            c.drawText("${it.colour} / Size ${it.size}  x${it.qty} @ ${money(prefs, it.price)}", 20f, y, normal)
            c.drawText(money(prefs, it.price * it.qty), W - 20f, y, right); y += 38f
        }
        c.drawLine(20f, y, W - 20f, y, normal); y += 40f
        c.drawText("TOTAL", 20f, y, bold)
        c.drawText(money(prefs, bill.total), W - 20f, y, rightBold); y += 36f
        c.drawText("Payment: ${bill.method}", 20f, y, normal); y += 50f
        c.drawText("Thank you! Visit again.", W / 2f, y, center)
        return Bitmap.createBitmap(bmp, 0, 0, W, (y + 40f).toInt().coerceAtMost(height))
    }

    /** One page: the bill picture scaled to the width of an A4 sheet (595 pt), as long as the bill is. */
    fun savePdf(ctx: Context, bmp: Bitmap, billNo: String): File {
        val doc = PdfDocument()
        val pageW = 595
        val scale = pageW.toFloat() / bmp.width
        val pageH = Math.ceil((bmp.height * scale).toDouble()).toInt().coerceAtLeast(1)
        val page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, 1).create())
        page.canvas.scale(scale, scale)
        page.canvas.drawBitmap(bmp, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        doc.finishPage(page)
        val dir = File(ctx.filesDir, "receipts").apply { mkdirs() }
        val f = File(dir, "Receipt-$billNo.pdf")
        FileOutputStream(f).use { doc.writeTo(it) }
        doc.close()
        return f
    }

    fun share(ctx: Context, file: File) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(i, "Share receipt").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /**
     * Opens WhatsApp with the PDF ready to send. With a 10-digit [mobile] the bill goes straight to that customer's chat
     * (no contact needed). Without a number the owner picks the chat. Falls back to the normal share list.
     */
    fun shareWhatsApp(ctx: Context, file: File, text: String, mobile: String = "") {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
        val digits = mobile.filter { it.isDigit() }
        val jid = if (digits.length == 10) "91$digits@s.whatsapp.net" else ""
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            try {
                val i = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    setPackage(pkg)
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TEXT, text)
                    if (jid.isNotEmpty()) putExtra("jid", jid)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(i)
                return
            } catch (e: Exception) { /* not installed: try the next one */ }
        }
        share(ctx, file)
    }

    /**
     * Copies the bill PDF to the phone's Downloads folder, so it can be picked in WhatsApp's attach (📎) menu.
     * Returns false if it could not be saved.
     */
    fun saveToDownloads(ctx: Context, file: File): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = ctx.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri == null) {
                    false
                } else {
                    resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    true
                }
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                file.copyTo(File(dir, file.name), overwrite = true)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * WhatsApp chat with this customer's number, opened directly (no contact saving, no search).
     * The bill text is filled in; the PDF is saved to Downloads, to attach with 📎.
     * Returns false if there is no 10-digit number or WhatsApp could not be opened.
     */
    fun whatsAppToCustomer(ctx: Context, file: File, text: String, mobile: String): Boolean {
        val digits = mobile.filter { it.isDigit() }.takeLast(10)
        if (digits.length != 10) return false
        val url = "https://wa.me/91$digits?text=" + Uri.encode(text)
        return try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("com.whatsapp")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (e2: Exception) { false }
        }
    }

    fun print(ctx: Context, bmp: Bitmap, jobName: String) {
        PrintHelper(ctx).apply { scaleMode = PrintHelper.SCALE_MODE_FIT }.printBitmap(jobName, bmp)
    }
}
