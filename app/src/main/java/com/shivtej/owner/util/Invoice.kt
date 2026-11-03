package com.shivtej.owner.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.PathParser
import com.shivtej.owner.R
import java.io.File

/**
 * Draws a finished bill as a picture.
 *   [invoice] = the colourful 1024 px wide bill (screen, PDF, share, normal printer)
 *   [thermal] = the black-and-white 576 dot wide bill for the 80mm Bluetooth printer
 * Only colours, background and which blocks are shown come from the theme - every number comes from [ReceiptData].
 */
object Invoice {
    const val W = 1024
    const val THERMAL_W = EscPos.DOTS_80MM

    // 24x24 Material icon outlines, drawn as small vector shapes (no image files needed)
    private const val ICON_PIN = "M12,2C8.13,2 5,5.13 5,9c0,5.25 7,13 7,13s7,-7.75 7,-13c0,-3.87 -3.13,-7 -7,-7zM12,11.5c-1.38,0 -2.5,-1.12 -2.5,-2.5s1.12,-2.5 2.5,-2.5 2.5,1.12 2.5,2.5 -1.12,2.5 -2.5,2.5z"
    private const val ICON_PHONE = "M6.62,10.79c1.44,2.83 3.76,5.14 6.59,6.59l2.2,-2.2c0.27,-0.27 0.67,-0.36 1.02,-0.24 1.12,0.37 2.33,0.57 3.57,0.57 0.55,0 1,0.45 1,1V20c0,0.55 -0.45,1 -1,1 -9.39,0 -17,-7.61 -17,-17 0,-0.55 0.45,-1 1,-1h3.5c0.55,0 1,0.45 1,1 0,1.25 0.2,2.45 0.57,3.57 0.11,0.35 0.03,0.74 -0.25,1.02l-2.2,2.2z"
    private const val ICON_MAIL = "M20,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.9,-2 -2,-2zM20,8l-8,5 -8,-5V6l8,5 8,-5v2z"
    private const val ICON_PERSON = "M12,12c2.21,0 4,-1.79 4,-4s-1.79,-4 -4,-4 -4,1.79 -4,4 1.79,4 4,4zM12,14c-2.67,0 -8,1.34 -8,4v2h16v-2c0,-2.66 -5.33,-4 -8,-4z"
    private const val ICON_DOC = "M14,2H6c-1.1,0 -1.99,0.9 -1.99,2L4,20c0,1.1 0.89,2 1.99,2H18c1.1,0 2,-0.9 2,-2V8l-6,-6zM16,18H8v-2h8v2zM16,14H8v-2h8v2zM13,9V3.5L18.5,9H13z"
    private const val ICON_CAL = "M19,4h-1V2h-2v2H8V2H6v2H5C3.89,4 3.01,4.9 3.01,6L3,20c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.9,-2 -2,-2zM19,20H5V10h14v10z"
    private const val ICON_CARD = "M20,4H4c-1.11,0 -1.99,0.89 -1.99,2L2,18c0,1.11 0.89,2 2,2h16c1.11,0 2,-0.89 2,-2V6c0,-1.11 -0.89,-2 -2,-2zM20,18H4v-6h16v6zM20,8H4V6h16v2z"
    private const val ICON_CART = "M7,18c-1.1,0 -1.99,0.9 -1.99,2S5.9,22 7,22s2,-0.9 2,-2 -0.9,-2 -2,-2zM1,2v2h2l3.6,7.59 -1.35,2.45c-0.16,0.28 -0.25,0.61 -0.25,0.96 0,1.1 0.9,2 2,2h12v-2H7.42c-0.14,0 -0.25,-0.11 -0.25,-0.25l0.03,-0.12 0.9,-1.63h7.45c0.75,0 1.41,-0.41 1.75,-1.03l3.58,-6.49c0.08,-0.14 0.12,-0.31 0.12,-0.48 0,-0.55 -0.45,-1 -1,-1H5.21l-0.94,-2H1zM17,18c-1.1,0 -1.99,0.9 -1.99,2s0.89,2 1.99,2 2,-0.9 2,-2 -0.9,-2 -2,-2z"
    private const val ICON_STORE = "M20,4H4v2h16V4zM21,14v-2l-1,-5H4l-1,5v2h1v6h10v-6h4v6h2v-6h1zM12,18H6v-4h6v4z"

    private val pathCache = HashMap<String, Path>()
    private fun iconPath(d: String): Path = pathCache.getOrPut(d) { PathParser.createPathFromPathData(d) }

    private fun drawIcon(c: Canvas, d: String, x: Float, y: Float, size: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        c.save()
        c.translate(x, y)
        c.scale(size / 24f, size / 24f)
        c.drawPath(iconPath(d), p)
        c.restore()
    }

    // ------------------------------------------------------------------ small helpers
    private fun paint(size: Float, color: Int, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; textSize = size; textAlign = align
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    /** Shortens a text with "..." so that it fits in [maxW] pixels. */
    private fun fit(text: String, p: Paint, maxW: Float): String {
        if (p.measureText(text) <= maxW) return text
        var t = text
        while (t.isNotEmpty() && p.measureText("$t...") > maxW) t = t.dropLast(1)
        return "$t..."
    }

    /** Word wrap by real pixel width. */
    private fun wrap(text: String, p: Paint, maxW: Float): List<String> {
        val out = ArrayList<String>()
        for (para in text.split("\n")) {
            var line = ""
            for (w in para.trim().split(" ").filter { it.isNotEmpty() }) {
                var word = w
                while (p.measureText(word) > maxW && word.length > 1) {
                    var cut = word.length - 1
                    while (cut > 1 && p.measureText(word.substring(0, cut)) > maxW) cut--
                    if (line.isNotEmpty()) { out.add(line); line = "" }
                    out.add(word.substring(0, cut)); word = word.substring(cut)
                }
                val tryLine = if (line.isEmpty()) word else "$line $word"
                if (p.measureText(tryLine) <= maxW) line = tryLine else { out.add(line); line = word }
            }
            out.add(line)
        }
        return out
    }

    private fun scaleToFit(b: Bitmap, maxW: Float, maxH: Float): RectF {
        val k = minOf(maxW / b.width, maxH / b.height)
        return RectF(0f, 0f, b.width * k, b.height * k)
    }

    /** The shop logo: the one picked in Shop settings, or the Shivtej Footwear logo built into the app. */
    fun loadLogo(ctx: Context, shop: ShopInfo): Bitmap? {
        try {
            if (shop.logoPath.isNotBlank() && File(shop.logoPath).exists()) {
                BitmapFactory.decodeFile(shop.logoPath)?.let { return it }
            }
        } catch (e: Exception) { /* fall back to the built-in logo */ }
        return try { BitmapFactory.decodeResource(ctx.resources, R.drawable.logo_shivtej) } catch (e: Exception) { null }
    }

    private fun usesBuiltInLogo(shop: ShopInfo): Boolean = shop.logoPath.isBlank() || !File(shop.logoPath).exists()

    private fun loadBackground(theme: BillTheme): Bitmap? =
        try { if (theme.bgStyle == "image" && theme.bgImage.isNotBlank() && File(theme.bgImage).exists()) BitmapFactory.decodeFile(theme.bgImage) else null }
        catch (e: Exception) { null }

    private fun qrFor(text: String, px: Int, dark: Int = Color.BLACK): Bitmap? = Qr.bitmap(text, px, dark, Color.WHITE)

    // ------------------------------------------------------------------ background
    private fun drawBackground(c: Canvas, w: Int, h: Int, t: BillTheme, logo: Bitmap?, bgImg: Bitmap?) {
        c.drawColor(Color.WHITE)
        val s = BillThemes.strength(t)
        if (s <= 0) return
        val a = (s / BillTheme.MAX_STRENGTH.toFloat() * 255f).toInt()
        when (t.bgStyle) {
            "tint" -> c.drawColor(withAlpha(t.tint, minOf(255, a * 2)))
            "lines" -> {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(t.accent, a / 2); strokeWidth = 3f }
                var x = -h
                while (x < w) { c.drawLine(x.toFloat(), h.toFloat(), (x + h).toFloat(), 0f, p); x += 44 }
            }
            "dots" -> {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(t.accent, a / 2) }
                var y = 20f
                while (y < h) { var x = 20f; while (x < w) { c.drawCircle(x, y, 3.5f, p); x += 34f }; y += 34f }
            }
            "watermark" -> if (logo != null) {
                val r = scaleToFit(logo, w * 0.78f, h * 0.5f)
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = minOf(120, a) }
                c.drawBitmap(logo, null, RectF((w - r.width()) / 2f, (h - r.height()) / 2f, (w + r.width()) / 2f, (h + r.height()) / 2f), p)
            }
            "image" -> if (bgImg != null) {
                val k = maxOf(w.toFloat() / bgImg.width, h.toFloat() / bgImg.height)
                val dw = bgImg.width * k
                val dh = bgImg.height * k
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = (a * 0.5f).toInt().coerceIn(0, 130) }
                c.drawBitmap(bgImg, null, RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f), p)
            }
            else -> { }
        }
    }

    // ------------------------------------------------------------------ colour invoice
    fun invoice(ctx: Context, d: ReceiptData, t: BillTheme): Bitmap {
        val logo = if (t.showLogo || t.bgStyle == "watermark") loadLogo(ctx, d.shop) else null
        val bg = loadBackground(t)
        val site = Links.website(d.shop.website)
        val insta = Links.instagram(d.shop.instagram)
        val qrSite = if (t.showQr && site.isNotEmpty()) qrFor(site, 300, t.accent2) else null
        val qrInsta = if (t.showInstagramQr && insta.isNotEmpty()) qrFor(insta, 300, t.accent2) else null

        val probe = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val height = Math.ceil(layoutInvoice(Canvas(probe), d, t, logo, qrSite, qrInsta).toDouble()).toInt().coerceAtLeast(400)
        probe.recycle()

        val bmp = Bitmap.createBitmap(W, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        drawBackground(c, W, height, t, logo, bg)
        layoutInvoice(c, d, t, logo, qrSite, qrInsta)
        return bmp
    }

    private fun card(c: Canvas, x: Float, y: Float, w: Float, h: Float, title: String, icon: String, t: BillTheme) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(Color.WHITE, 238); style = Paint.Style.FILL }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(t.accent, 90); style = Paint.Style.STROKE; strokeWidth = 2f }
        c.drawRoundRect(RectF(x, y, x + w, y + h), 14f, 14f, fill)
        c.drawRoundRect(RectF(x, y, x + w, y + h), 14f, 14f, line)
        val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.accent; style = Paint.Style.FILL }
        val clip = Path().apply { addRoundRect(RectF(x, y, x + w, y + 46f), floatArrayOf(14f, 14f, 14f, 14f, 0f, 0f, 0f, 0f), Path.Direction.CW) }
        c.drawPath(clip, head)
        drawIcon(c, icon, x + 14f, y + 10f, 26f, Color.WHITE)
        c.drawText(title, x + 52f, y + 33f, paint(28f, Color.WHITE, true))
    }

    private fun kv(c: Canvas, x: Float, y: Float, w: Float, icon: String, key: String, value: String, t: BillTheme, valueColor: Int = Color.BLACK) {
        drawIcon(c, icon, x + 14f, y - 22f, 24f, t.accent2)
        c.drawText(key, x + 50f, y, paint(25f, Color.BLACK, true))
        c.drawText(":", x + 232f, y, paint(25f, Color.BLACK))
        val vp = paint(25f, valueColor)
        c.drawText(fit(value, vp, w - 250f - 10f), x + 250f, y, vp)
    }

    /**
     * Bill design A1 (chosen by the owner, Nov 2026): white and clean, shop name in the middle, the QR codes at the top
     * right (same place as before), details in two columns, a lined item table, totals on the right, notes at the end.
     * The same layout draws a normal bill, an EXCHANGE bill and a RETURN bill ([ReceiptData.kind]).
     */
    private fun layoutInvoice(c: Canvas, d: ReceiptData, t: BillTheme, logo: Bitmap?, qrSite: Bitmap?, qrInsta: Bitmap?): Float {
        val m = 30f
        val inner = W - 2 * m
        val cur = d.shop.currency.ifBlank { "₹" }
        val ink = Color.BLACK
        val grey = Color.rgb(90, 90, 90)
        val green = Color.rgb(46, 125, 50)
        val blue = Color.rgb(40, 53, 147)
        val light = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(225, 225, 225); strokeWidth = 1.5f }
        val heavy = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; strokeWidth = 2.5f }
        val redLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.accent; strokeWidth = 3f }

        // red band at the very top
        c.drawRect(0f, 0f, W.toFloat(), 12f, Paint().apply { color = t.accent })
        var y = 28f

        // ---------------- header: QR codes at the top right (as before), shop name / logo in the middle of the rest
        val site = Links.website(d.shop.website)
        val qrSize = 140f
        var qrRight = W - m
        val qrTop = y
        var qrBottom = y
        fun drawQr(b: Bitmap?, l1: String, l2: String) {
            if (b == null) return
            val x = qrRight - qrSize
            val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.accent; style = Paint.Style.FILL }
            c.drawRoundRect(RectF(x - 6f, qrTop, x + qrSize + 6f, qrTop + qrSize + 54f), 12f, 12f, box)
            c.drawBitmap(b, null, RectF(x, qrTop + 6f, x + qrSize, qrTop + 6f + qrSize), null)
            val lp = paint(18f, Color.WHITE, true, Paint.Align.CENTER)
            c.drawText(l1, x + qrSize / 2f, qrTop + qrSize + 27f, lp)
            c.drawText(l2, x + qrSize / 2f, qrTop + qrSize + 48f, lp)
            qrRight = x - 22f
            qrBottom = qrTop + qrSize + 54f
        }
        drawQr(qrInsta, "FOLLOW US ON", "INSTAGRAM")
        drawQr(qrSite, "VISIT OUR", "WEBSITE")
        val rightEdge = qrRight
        val cx = (m + rightEdge) / 2f
        val colW = rightEdge - m
        val builtIn = usesBuiltInLogo(d.shop)
        var yy = y
        if (t.showLogo && logo != null) {
            val r = scaleToFit(logo, minOf(420f, colW), 120f)
            c.drawBitmap(logo, null, RectF(cx - r.width() / 2f, yy, cx + r.width() / 2f, yy + r.height()), null)
            yy += r.height() + 6f
        }
        if (!(t.showLogo && logo != null && builtIn)) {
            val np = paint(46f, t.accent, true, Paint.Align.CENTER)
            c.drawText(fit(d.shop.name.uppercase(), np, colW), cx, yy + 42f, np); yy += 54f
        }
        if (t.showTagline && d.shop.tagline.isNotBlank()) {
            val tp = paint(20f, grey, true, Paint.Align.CENTER); tp.letterSpacing = 0.25f
            c.drawText(fit(d.shop.tagline.uppercase(), tp, colW), cx, yy + 22f, tp); yy += 30f
        }
        val cp = paint(23f, ink, false, Paint.Align.CENTER)
        for (l in wrap(d.shop.address, cp, colW)) if (l.isNotBlank()) { c.drawText(l, cx, yy + 22f, cp); yy += 29f }
        val contact = listOf(
            if (d.shop.phone.isNotBlank()) "Ph: " + d.shop.phone else "",
            d.shop.email,
            if (site.isNotEmpty()) Links.shown(site) else ""
        ).filter { it.isNotBlank() }
        for (l in wrap(contact.joinToString("  ·  "), cp, colW)) if (l.isNotBlank()) { c.drawText(l, cx, yy + 22f, cp); yy += 29f }
        // the GSTIN shows only on a GST bill
        if (d.isGst) {
            val gp = paint(24f, ink, true, Paint.Align.CENTER)
            val txt = "GSTIN: " + d.gstinShown
            val tw = gp.measureText(txt)
            c.drawRoundRect(RectF(cx - tw / 2f - 12f, yy + 2f, cx + tw / 2f + 12f, yy + 34f), 6f, 6f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 2f })
            c.drawText(txt, cx, yy + 26f, gp); yy += 40f
        }
        y = maxOf(yy, qrBottom) + 10f
        c.drawLine(m, y, W - m, y, redLine)
        y += 12f

        // ---------------- TAX INVOICE band (GST bill)
        if (d.isGst) {
            c.drawRoundRect(RectF(m, y, W - m, y + 50f), 8f, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink })
            val bp = paint(30f, Color.WHITE, true, Paint.Align.CENTER); bp.letterSpacing = 0.2f
            c.drawText("TAX INVOICE", W / 2f, y + 36f, bp)
            y += 62f
        }
        // ---------------- EXCHANGE / RETURN band
        if (d.kind == "EXCHANGE" || d.kind == "RETURN") {
            val bandP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (d.kind == "EXCHANGE") blue else t.accent }
            c.drawRoundRect(RectF(m, y, W - m, y + 50f), 8f, 8f, bandP)
            val bp = paint(30f, Color.WHITE, true, Paint.Align.CENTER); bp.letterSpacing = 0.2f
            c.drawText(if (d.kind == "EXCHANGE") "EXCHANGE BILL" else "RETURN BILL", W / 2f, y + 36f, bp)
            y += 62f
        }

        // ---------------- details in two columns
        val isRet = d.kind == "EXCHANGE" || d.kind == "RETURN"
        val rows = ArrayList<Pair<Pair<String, String>, Pair<String, String>>>()
        rows.add(("Bill No" to d.billNo) to ("Date" to Fmt.dateTime(d.dateMillis)))
        if (isRet && d.oldBillNo.isNotBlank()) rows.add(("Old Bill" to d.oldBillNo) to ("Sales Person" to d.salesPerson.ifBlank { "-" }))
        rows.add(("Customer" to d.customerName.ifBlank { "Walk-in Customer" }) to ("Mobile" to d.customerMobile.ifBlank { "-" }))
        if (isRet && d.oldBillNo.isNotBlank()) rows.add(("Address" to d.customerAddress.ifBlank { "-" }) to
            ((if (d.kind == "RETURN") "Refund" else "Payment") to d.method.ifBlank { "-" }))
        else {
            rows.add(("Sales Person" to d.salesPerson.ifBlank { "-" }) to ("Payment" to d.method.ifBlank { "-" }))
            if (d.customerAddress.isNotBlank()) rows.add(("Address" to d.customerAddress) to ("" to ""))
        }
        val kp = paint(24f, ink, true)
        val vp = paint(24f, ink)
        val half = inner / 2f
        for ((l, r) in rows) {
            val lk = l.first + ": "
            c.drawText(lk, m, y + 26f, kp)
            c.drawText(fit(l.second, vp, half - kp.measureText(lk) - 10f), m + kp.measureText(lk), y + 26f, vp)
            if (r.first.isNotBlank()) {
                val rk = r.first + ": "
                val rv = fit(r.second, vp, half - kp.measureText(rk) - 10f)
                val rvW = vp.measureText(rv)
                c.drawText(rv, W - m - rvW, y + 26f, vp)
                c.drawText(rk, W - m - rvW - kp.measureText(rk), y + 26f, kp)
            }
            y += 34f
        }
        y += 8f

        // ---------------- item tables (one for a sale; RETURNED + NEW ITEM for an exchange)
        val cols = floatArrayOf(52f, 210f, 170f, 86f, 72f, 116f, 116f, 142f)
        val heads = listOf("#", "Article", "Colour", "Size", "Qty", "MRP ($cur)", "Rate ($cur)", "Amount ($cur)")
        fun table(title: String?, titleColor: Int, list: List<RLine>, sign: String, signColor: Int) {
            if (title != null) {
                c.drawText(title, m, y + 26f, paint(25f, titleColor, true)); y += 36f
            }
            c.drawLine(m, y, W - m, y, heavy)
            var x = m
            val hp = paint(22f, ink, true)
            for (i in cols.indices) {
                if (i == cols.lastIndex) c.drawText(heads[i], x + cols[i] - 6f, y + 32f, paint(22f, ink, true, Paint.Align.RIGHT))
                else c.drawText(fit(heads[i], hp, cols[i] - 8f), x + 6f, y + 32f, hp)
                x += cols[i]
            }
            y += 46f
            c.drawLine(m, y, W - m, y, heavy)
            for (l in list) {
                val tp = paint(24f, ink)
                val cells = listOf(l.sr.toString(), (l.article.ifBlank { l.name }).uppercase(), l.colour.uppercase(), l.size,
                    l.qty.toString(), Fmt.indian(l.mrp), Fmt.indian(l.rate))
                x = m
                for (i in cells.indices) { c.drawText(fit(cells[i], tp, cols[i] - 10f), x + 6f, y + 33f, tp); x += cols[i] }
                val ap = paint(24f, if (sign.isBlank()) ink else signColor, sign.isNotBlank(), Paint.Align.RIGHT)
                c.drawText(sign + Fmt.indian(l.amount), W - m - 6f, y + 33f, ap)
                y += 48f
                c.drawLine(m, y, W - m, y, light)
            }
            y += 10f
        }
        when (d.kind) {
            "EXCHANGE" -> {
                table("↩ RETURNED", t.accent, d.returnLines, "− ", t.accent)
                table("+ NEW ITEM", green, d.lines, "+ ", green)
            }
            "RETURN" -> table("↩ RETURNED", t.accent, d.returnLines, "− ", t.accent)
            else -> table(null, ink, d.lines, "", ink)
        }

        // ---------------- totals on the right
        val totW = 440f
        val tx = W - m - totW
        val lp = paint(25f, ink)
        fun trow(label: String, value: String, color: Int = ink) {
            c.drawText(label, tx, y + 28f, lp)
            c.drawText(value, W - m, y + 28f, paint(25f, color, color != ink, Paint.Align.RIGHT))
            y += 36f
        }
        fun grand(label: String, value: String, color: Int) {
            val lineP = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; strokeWidth = 3f }
            y += 4f
            c.drawLine(tx, y, W - m, y, lineP)
            c.drawText(label, tx, y + 42f, paint(32f, color, true))
            c.drawText(value, W - m, y + 42f, paint(34f, color, true, Paint.Align.RIGHT))
            y += 58f
            c.drawLine(tx, y, W - m, y, lineP)
            y += 6f
        }
        when (d.kind) {
            "EXCHANGE" -> {
                trow("New items", Fmt.moneyG(cur, d.newValue))
                trow("Return credit", "− " + Fmt.moneyG(cur, d.returnValue), t.accent)
                when {
                    d.refund < -0.005 -> grand("Customer pays", Fmt.moneyG(cur, -d.refund), t.accent)
                    d.refund > 0.005 -> grand("Refund to customer", Fmt.moneyG(cur, d.refund), green)
                    else -> grand("Difference", Fmt.moneyG(cur, 0.0), t.accent)
                }
                val left = BillMath.round2(d.newValue - d.returnValue + d.refund)
                if (kotlin.math.abs(left) > 0.005) trow(if (left > 0) "Added to udhaar" else "Adjusted in udhaar", Fmt.moneyG(cur, kotlin.math.abs(left)))
            }
            "RETURN" -> {
                trow("Return", "− " + Fmt.moneyG(cur, d.returnValue), t.accent)
                grand("Refund to customer", Fmt.moneyG(cur, maxOf(0.0, d.refund)), green)
                val adj = BillMath.round2(d.returnValue - maxOf(0.0, d.refund))
                if (adj > 0.005) trow("Adjusted in udhaar", Fmt.moneyG(cur, adj))
            }
            else -> {
                trow("Total MRP", Fmt.moneyG(cur, d.totalMrp))
                trow("Discount", "− " + Fmt.moneyG(cur, d.totalDiscount), t.accent)
                if (d.isGst) {
                    trow("Taxable Value", Fmt.moneyG(cur, d.taxableValue))
                    trow("CGST @" + d.halfRate + "%", Fmt.moneyG(cur, d.cgst))
                    trow("SGST @" + d.halfRate + "%", Fmt.moneyG(cur, d.sgst))
                }
                grand("Grand Total", Fmt.moneyG(cur, d.grandTotal), t.accent)
                trow("Paid", Fmt.moneyG(cur, d.amountPaid))
                trow("Balance", Fmt.moneyG(cur, d.balance), if (d.balance <= 0.005) green else t.accent)
                // GST summary (like the supplier's bill): taxable value, CGST, SGST, total tax
                if (d.isGst) {
                    y += 8f
                    val sw = inner / 4f
                    val heads2 = listOf("Taxable Value", "CGST " + d.halfRate + "%", "SGST " + d.halfRate + "%", "Total Tax")
                    val vals2 = listOf(Fmt.indian(d.taxableValue), Fmt.indian(d.cgst), Fmt.indian(d.sgst), Fmt.indian(d.taxAmount))
                    val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(150, 150, 150); style = Paint.Style.STROKE; strokeWidth = 1.5f }
                    c.drawRect(m, y, W - m, y + 84f, box)
                    c.drawLine(m, y + 40f, W - m, y + 40f, box)
                    for (i in 0 until 4) {
                        val x0 = m + i * sw
                        if (i > 0) c.drawLine(x0, y, x0, y + 84f, box)
                        c.drawText(heads2[i], x0 + sw / 2f, y + 28f, paint(21f, ink, true, Paint.Align.CENTER))
                        c.drawText(vals2[i], x0 + sw / 2f, y + 70f, paint(22f, ink, false, Paint.Align.CENTER))
                    }
                    y += 96f
                }
            }
        }
        y += 10f

        // ---------------- notes (after a dashed line)
        if (t.showNotes && d.shop.notes.isNotEmpty()) {
            val dash = Paint().apply { color = Color.rgb(170, 170, 170); strokeWidth = 2f; pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 7f), 0f) }
            c.drawLine(m, y, W - m, y, dash)
            y += 14f
            val np = paint(22f, Color.rgb(50, 50, 50))
            for (n in d.shop.notes) for (l in wrap(n, np, inner)) { c.drawText(l, m, y + 22f, np); y += 29f }
            y += 6f
        }

        // ---------------- thank you
        if (t.showThanks) {
            val th = d.shop.thanks.ifBlank { "Thank you!" }.uppercase()
            val tp = paint(34f, t.accent, true, Paint.Align.CENTER)
            c.drawText(fit("$th VISIT AGAIN", tp, inner), W / 2f, y + 40f, tp)
            y += 56f
        }
        if (t.showFooter && d.shop.footer.isNotBlank()) {
            val fp = paint(22f, grey, false, Paint.Align.CENTER)
            for (l in wrap(d.shop.footer, fp, inner - 40f)) { c.drawText(l, W / 2f, y + 22f, fp); y += 29f }
        }
        return y + 24f
    }

    // ------------------------------------------------------------------ 80mm thermal bill (black and white)
    fun thermal(ctx: Context, d: ReceiptData, t: BillTheme): Bitmap {
        val logo = if (t.showLogo) loadLogo(ctx, d.shop) else null
        val site = Links.website(d.shop.website)
        val insta = Links.instagram(d.shop.instagram)
        val qrSite = if (t.showQr && site.isNotEmpty()) qrFor(site, 230) else null
        val qrInsta = if (t.showInstagramQr && insta.isNotEmpty()) qrFor(insta, 230) else null
        val probe = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val h = Math.ceil(layoutThermal(Canvas(probe), ctx, d, t, logo, qrSite, qrInsta).toDouble()).toInt().coerceAtLeast(200)
        probe.recycle()
        val bmp = Bitmap.createBitmap(THERMAL_W, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        layoutThermal(c, ctx, d, t, logo, qrSite, qrInsta)
        return bmp
    }

    private fun layoutThermal(c: Canvas, ctx: Context, d: ReceiptData, t: BillTheme, logo: Bitmap?, qrSite: Bitmap?, qrInsta: Bitmap?): Float {
        val w = THERMAL_W.toFloat()
        val m = 6f
        val cur = d.shop.currency.ifBlank { "₹" }
        val ink = Color.BLACK
        val cen = paint(24f, ink, false, Paint.Align.CENTER)
        val norm = paint(24f, ink)
        val bold = paint(24f, ink, true)
        val right = paint(24f, ink, false, Paint.Align.RIGHT)
        val rightBold = paint(24f, ink, true, Paint.Align.RIGHT)
        val dash = Paint().apply { color = ink; strokeWidth = 2f; pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f, 6f), 0f) }
        var y = 8f
        fun rule() { y += 6f; c.drawLine(m, y, w - m, y, dash); y += 14f }
        fun lr(l: String, r: String, lp: Paint = norm, rp: Paint = right) {
            c.drawText(fit(l, lp, w - 2 * m - rp.measureText(r) - 10f), m, y + 22f, lp)
            c.drawText(r, w - m, y + 22f, rp); y += 30f
        }

        val builtIn = usesBuiltInLogo(d.shop)
        if (t.showLogo && logo != null) {
            val r = scaleToFit(logo, 400f, 190f)
            c.drawBitmap(logo, null, RectF((w - r.width()) / 2f, y, (w + r.width()) / 2f, y + r.height()), null)
            y += r.height() + 6f
        }
        if (!(t.showLogo && logo != null && builtIn)) {
            val np = paint(38f, ink, true, Paint.Align.CENTER)
            c.drawText(fit(d.shop.name.uppercase(), np, w - 2 * m), w / 2f, y + 34f, np); y += 44f
        }
        if (t.showTagline && d.shop.tagline.isNotBlank()) { c.drawText(fit(d.shop.tagline, cen, w - 2 * m), w / 2f, y + 22f, cen); y += 28f }
        for (l in wrap(d.shop.address, cen, w - 2 * m)) if (l.isNotBlank()) { c.drawText(l, w / 2f, y + 22f, cen); y += 28f }
        if (d.shop.phone.isNotBlank()) { c.drawText("Ph: " + d.shop.phone, w / 2f, y + 22f, cen); y += 28f }
        if (d.shop.email.isNotBlank()) { c.drawText(fit(d.shop.email, cen, w - 2 * m), w / 2f, y + 22f, cen); y += 28f }
        if (d.isGst) {
            c.drawText("GSTIN: " + d.gstinShown, w / 2f, y + 22f, paint(24f, ink, true, Paint.Align.CENTER)); y += 30f
            c.drawText("TAX INVOICE", w / 2f, y + 30f, paint(32f, ink, true, Paint.Align.CENTER)); y += 42f
        }
        rule()

        if (d.kind == "EXCHANGE" || d.kind == "RETURN") {
            c.drawText(if (d.kind == "EXCHANGE") "EXCHANGE BILL" else "RETURN BILL", w / 2f, y + 30f, paint(32f, ink, true, Paint.Align.CENTER)); y += 42f
        }
        lr("Bill No: " + d.billNo, "", bold)
        if (d.oldBillNo.isNotBlank()) { c.drawText("Old Bill: " + d.oldBillNo, m, y + 22f, norm); y += 30f }
        c.drawText(Fmt.dateTime(d.dateMillis), m, y + 22f, norm); y += 30f
        val cust = d.customerName.ifBlank { "Walk-in Customer" } + (if (d.customerMobile.isNotBlank()) " (" + d.customerMobile + ")" else "")
        for (l in wrap("Customer: $cust", norm, w - 2 * m)) { c.drawText(l, m, y + 22f, norm); y += 29f }
        if (d.salesPerson.isNotBlank()) { c.drawText("Sales Person: " + d.salesPerson, m, y + 22f, norm); y += 29f }
        rule()

        if (d.kind == "EXCHANGE" || d.kind == "RETURN") {
            c.drawText("RETURNED", m, y + 22f, bold); y += 30f
            for (l in d.returnLines) {
                c.drawText(fit("${l.sr}. " + (l.article.ifBlank { l.name }).uppercase(), bold, w - 2 * m), m, y + 22f, bold); y += 29f
                lr("   " + l.colour.uppercase() + " / " + l.size + "   " + l.qty + " x " + Fmt.indian(l.rate), "-" + Fmt.indian(l.amount))
            }
            rule()
            if (d.kind == "EXCHANGE") { c.drawText("NEW ITEM", m, y + 22f, bold); y += 30f }
        }
        for (l in d.lines) {
            val title = (l.article.ifBlank { l.name }).uppercase() +
                (if (l.article.isNotBlank() && l.name.isNotBlank() && !l.name.equals(l.article, true)) "  " + l.name else "")
            for ((i, tl) in wrap("${l.sr}. $title", bold, w - 2 * m).withIndex()) { c.drawText(tl, m, y + 22f, bold); y += 29f }
            lr("   " + l.colour.uppercase() + " / " + l.size + "   " + l.qty + " x " + Fmt.indian(l.rate), Fmt.indian(l.amount))
            if (l.mrp > l.rate) { c.drawText("   MRP " + Fmt.indian(l.mrp), m, y + 20f, paint(21f, Color.rgb(60, 60, 60))); y += 26f }
        }
        rule()
        if (d.kind == "EXCHANGE" || d.kind == "RETURN") {
            if (d.kind == "EXCHANGE") lr("New items", Fmt.moneyG(cur, d.newValue))
            lr("Return credit", "-" + Fmt.moneyG(cur, d.returnValue))
            rule()
            val gp2 = paint(30f, ink, true)
            val gr2 = paint(32f, ink, true, Paint.Align.RIGHT)
            val (label, value) = if (d.refund < -0.005) "CUSTOMER PAYS" to -d.refund else "REFUND" to maxOf(0.0, d.refund)
            c.drawText(label, m, y + 30f, gp2); c.drawText(Fmt.moneyG(cur, value), w - m, y + 30f, gr2); y += 42f
            rule()
            lr(if (d.kind == "RETURN") "Refund Mode" else "Payment Mode", d.method)
        } else {
        lr("Total MRP", Fmt.moneyG(cur, d.totalMrp))
        lr("Total Discount", "-" + Fmt.moneyG(cur, d.totalDiscount))
        if (d.isGst) {
            lr("Taxable Value", Fmt.moneyG(cur, d.taxableValue))
            lr("CGST @" + d.halfRate + "%", Fmt.moneyG(cur, d.cgst))
            lr("SGST @" + d.halfRate + "%", Fmt.moneyG(cur, d.sgst))
        }
        rule()
        val gp = paint(32f, ink, true)
        val gr = paint(34f, ink, true, Paint.Align.RIGHT)
        c.drawText("GRAND TOTAL", m, y + 30f, gp)
        c.drawText(Fmt.moneyG(cur, d.grandTotal), w - m, y + 30f, gr); y += 42f
        rule()
        lr("Payment Mode", d.method)
        lr("Amount Paid", Fmt.moneyG(cur, d.amountPaid))
        lr("Balance", Fmt.moneyG(cur, d.balance), bold, rightBold)
        }

        if (t.showNotes && d.shop.notes.isNotEmpty()) {
            rule()
            val sp = paint(22f, ink)
            for (n in d.shop.notes) for ((i, l) in wrap(n, sp, w - 2 * m - 20f).withIndex()) {
                c.drawText(if (i == 0) "* $l" else "  $l", m, y + 20f, sp); y += 27f
            }
        }
        if (qrSite != null || qrInsta != null) {
            rule()
            val both = qrSite != null && qrInsta != null
            val size = if (both) 230f else 240f
            val gap = 30f
            val total = if (both) size * 2 + gap else size
            var x = (w - total) / 2f
            val cap = paint(20f, ink, true, Paint.Align.CENTER)
            if (qrSite != null) {
                c.drawBitmap(qrSite, null, RectF(x, y, x + size, y + size), null)
                c.drawText("Scan: our website", x + size / 2f, y + size + 22f, cap); x += size + gap
            }
            if (qrInsta != null) {
                c.drawBitmap(qrInsta, null, RectF(x, y, x + size, y + size), null)
                c.drawText("Scan: Instagram", x + size / 2f, y + size + 22f, cap)
            }
            y += size + 32f
        }
        rule()
        if (t.showThanks) {
            c.drawText(d.shop.thanks.ifBlank { "Thank you!" }, w / 2f, y + 30f, paint(30f, ink, true, Paint.Align.CENTER)); y += 38f
            c.drawText("VISIT AGAIN", w / 2f, y + 22f, cen); y += 30f
        }
        if (t.showFooter && d.shop.footer.isNotBlank()) for (l in wrap(d.shop.footer, cen, w - 2 * m)) { c.drawText(l, w / 2f, y + 22f, cen); y += 28f }
        return y + 16f
    }
}
