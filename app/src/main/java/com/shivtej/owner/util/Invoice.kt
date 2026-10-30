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

    private fun layoutInvoice(c: Canvas, d: ReceiptData, t: BillTheme, logo: Bitmap?, qrSite: Bitmap?, qrInsta: Bitmap?): Float {
        val m = 28f
        val inner = W - 2 * m
        val cur = d.shop.currency.ifBlank { "₹" }
        val ink = Color.BLACK
        val grey = Color.rgb(90, 90, 90)
        var y = 26f

        // ---------------- header: logo | contacts | QR codes
        val headH = 205f
        val site = Links.website(d.shop.website)
        val insta = Links.instagram(d.shop.instagram)
        val qrSize = 150f
        var qrRight = W - m
        fun drawQr(b: Bitmap?, l1: String, l2: String) {
            if (b == null) return
            val x = qrRight - qrSize
            val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.accent; style = Paint.Style.FILL }
            c.drawRoundRect(RectF(x - 6f, y, x + qrSize + 6f, y + qrSize + 56f), 14f, 14f, box)
            c.drawBitmap(b, null, RectF(x, y + 6f, x + qrSize, y + 6f + qrSize), null)
            val lp = paint(19f, Color.WHITE, true, Paint.Align.CENTER)
            c.drawText(l1, x + qrSize / 2f, y + qrSize + 28f, lp)
            c.drawText(l2, x + qrSize / 2f, y + qrSize + 50f, lp)
            qrRight = x - 24f
        }
        drawQr(qrSite, "VISIT OUR", "WEBSITE")
        drawQr(qrInsta, "FOLLOW US ON", "INSTAGRAM")
        val rightEdge = qrRight

        val builtIn = usesBuiltInLogo(d.shop)
        val nameP = paint(46f, t.accent, true)
        if (t.centerHeader) {
            // everything centred under each other
            val cx = (m + rightEdge) / 2f
            var yy = y
            if (t.showLogo && logo != null) {
                val r = scaleToFit(logo, 460f, 110f)
                c.drawBitmap(logo, null, RectF(cx - r.width() / 2f, yy, cx + r.width() / 2f, yy + r.height()), null)
                yy += r.height() + 8f
            }
            if (!(t.showLogo && logo != null && builtIn)) {
                val np = paint(44f, t.accent, true, Paint.Align.CENTER)
                c.drawText(fit(d.shop.name.uppercase(), np, rightEdge - m), cx, yy + 40f, np); yy += 52f
            }
            if (t.showTagline && d.shop.tagline.isNotBlank()) {
                c.drawText(d.shop.tagline, cx, yy + 22f, paint(22f, grey, false, Paint.Align.CENTER)); yy += 32f
            }
            val cp = paint(23f, ink, false, Paint.Align.CENTER)
            for (l in wrap(d.shop.address, cp, rightEdge - m)) { if (l.isNotBlank()) { c.drawText(l, cx, yy + 22f, cp); yy += 29f } }
            val line2 = listOf(
                if (d.shop.phone.isNotBlank()) "Ph: " + d.shop.phone else "",
                d.shop.email,
                if (site.isNotEmpty()) Links.shown(site) else ""
            ).filter { it.isNotBlank() }.joinToString("   |   ")
            if (line2.isNotBlank()) { c.drawText(fit(line2, cp, rightEdge - m), cx, yy + 22f, cp); yy += 29f }
            if (d.shop.gstin.isNotBlank()) { c.drawText("GSTIN: " + d.shop.gstin, cx, yy + 22f, cp); yy += 29f }
            y = maxOf(y + headH, yy + 10f)
        } else {
            var xText = m
            if (t.showLogo && logo != null) {
                val r = scaleToFit(logo, 340f, 150f)
                c.drawBitmap(logo, null, RectF(m, y + (150f - r.height()) / 2f, m + r.width(), y + (150f - r.height()) / 2f + r.height()), null)
                if (!builtIn) {
                    val np = paint(26f, t.accent, true)
                    c.drawText(fit(d.shop.name.uppercase(), np, 340f), m, y + 150f + 30f, np)
                }
                xText = m + 372f
                val div = Paint().apply { color = Color.LTGRAY; strokeWidth = 2f }
                c.drawLine(xText - 18f, y + 8f, xText - 18f, y + 150f, div)
            } else {
                val np = paint(44f, t.accent, true)
                c.drawText(fit(d.shop.name.uppercase(), np, 560f), m, y + 60f, np)
                if (t.showTagline && d.shop.tagline.isNotBlank()) c.drawText(d.shop.tagline, m, y + 96f, paint(22f, grey))
                xText = m + 600f
            }
            val cp = paint(25f, ink)
            val maxTextW = rightEdge - xText
            var yy = y + 8f
            if (maxTextW > 120f) {
                if (d.shop.address.isNotBlank()) {
                    drawIcon(c, ICON_PIN, xText, yy + 2f, 28f, t.accent)
                    val lines = wrap(d.shop.address, cp, maxTextW - 40f).take(2)
                    for (l in lines) { c.drawText(l, xText + 40f, yy + 24f, cp); yy += 31f }
                    yy += 4f
                }
                if (d.shop.phone.isNotBlank()) { drawIcon(c, ICON_PHONE, xText, yy + 2f, 28f, t.accent); c.drawText(fit(d.shop.phone, cp, maxTextW - 40f), xText + 40f, yy + 24f, cp); yy += 35f }
                if (d.shop.email.isNotBlank()) { drawIcon(c, ICON_MAIL, xText, yy + 2f, 28f, t.accent); c.drawText(fit(d.shop.email, cp, maxTextW - 40f), xText + 40f, yy + 24f, cp); yy += 35f }
                if (site.isNotEmpty() && yy < y + 150f) { drawIcon(c, ICON_DOC, xText, yy + 2f, 28f, t.accent); c.drawText(fit(Links.shown(site), cp, maxTextW - 40f), xText + 40f, yy + 24f, cp); yy += 35f }
                if (d.shop.gstin.isNotBlank() && yy < y + 175f) { c.drawText("GSTIN: " + d.shop.gstin, xText + 40f, yy + 24f, paint(23f, grey)) }
            }
            y += headH
        }
        y += 8f

        // ---------------- bill no + date
        val halfW = (inner - 16f) / 2f
        fun pill(x: Float, label: String, icon: String, labelW: Float, value: String, vSize: Float) {
            val box = RectF(x, y, x + halfW, y + 60f)
            c.drawRoundRect(box, 12f, 12f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(t.tint, 235); style = Paint.Style.FILL })
            c.drawRoundRect(box, 12f, 12f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(t.accent, 90); style = Paint.Style.STROKE; strokeWidth = 2f })
            val lab = Path().apply { addRoundRect(RectF(x, y, x + labelW, y + 60f), floatArrayOf(12f, 12f, 0f, 0f, 0f, 0f, 12f, 12f), Path.Direction.CW) }
            c.drawPath(lab, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.accent; style = Paint.Style.FILL })
            drawIcon(c, icon, x + 12f, y + 15f, 30f, Color.WHITE)
            c.drawText(label, x + 50f, y + 40f, paint(27f, Color.WHITE, true))
            val vp = paint(vSize, ink, true)
            c.drawText(fit(value, vp, halfW - labelW - 24f), x + labelW + 16f, y + 41f, vp)
        }
        pill(m, "Bill No.", ICON_DOC, 164f, d.billNo, 34f)
        pill(m + halfW + 16f, "Date & Time", ICON_CAL, 218f, Fmt.dateTime(d.dateMillis), 26f)
        y += 60f + 14f

        // ---------------- customer + sales details
        val leftW = 540f
        val rightW = inner - leftW - 14f
        val cardH = 46f + 3 * 44f + 14f
        card(c, m, y, leftW, cardH, "Customer Details", ICON_PERSON, t)
        card(c, m + leftW + 14f, y, rightW, cardH, "Sales Details", ICON_PERSON, t)
        val cust = d.customerName.ifBlank { "Walk-in Customer" }
        kv(c, m, y + 46f + 36f, leftW, ICON_PERSON, "Customer Name", cust, t)
        kv(c, m, y + 46f + 80f, leftW, ICON_PHONE, "Mobile Number", d.customerMobile.ifBlank { "-" }, t)
        kv(c, m, y + 46f + 124f, leftW, ICON_PIN, "City / Address", d.customerAddress.ifBlank { "-" }, t)
        val rx = m + leftW + 14f
        fun kv2(yy: Float, icon: String, key: String, value: String) {
            drawIcon(c, icon, rx + 12f, yy - 22f, 24f, t.accent2)
            c.drawText(key, rx + 44f, yy, paint(24f, ink, true))
            c.drawText(":", rx + 200f, yy, paint(24f, ink))
            val vp = paint(24f, ink)
            c.drawText(fit(value, vp, rightW - 215f - 8f), rx + 214f, yy, vp)
        }
        kv2(y + 46f + 36f, ICON_PERSON, "Sales Person", d.salesPerson.ifBlank { "-" })
        kv2(y + 46f + 80f, ICON_STORE, "Store", d.shop.name)
        kv2(y + 46f + 124f, ICON_CART, "Payment Mode", d.method)
        y += cardH + 14f

        // ---------------- items table
        val cols = floatArrayOf(62f, 200f, 170f, 92f, 80f, 122f, 122f, 120f)
        val heads = listOf("Sr", "Article", "Colour", "Size", "Qty", "MRP ($cur)", "Rate ($cur)", "Amount ($cur)")
        val rowH = 46f
        val headRowH = 54f
        val shown = maxOf(d.lines.size, 4)
        val tableH = headRowH + rowH * shown
        val tb = RectF(m, y, m + inner, y + tableH)
        c.drawRoundRect(tb, 12f, 12f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(Color.WHITE, 238); style = Paint.Style.FILL })
        val headBg = Path().apply { addRoundRect(RectF(m, y, m + inner, y + headRowH), floatArrayOf(12f, 12f, 12f, 12f, 0f, 0f, 0f, 0f), Path.Direction.CW) }
        c.drawPath(headBg, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(t.tint, 255); style = Paint.Style.FILL })
        val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(190, 190, 190); strokeWidth = 1.5f }
        var cx0 = m
        val hp = paint(24f, ink, true, Paint.Align.CENTER)
        for (i in cols.indices) {
            if (i > 0) c.drawLine(cx0, y, cx0, y + tableH, grid)
            c.drawText(fit(heads[i], hp, cols[i] - 8f), cx0 + cols[i] / 2f, y + 35f, hp)
            cx0 += cols[i]
        }
        for (r in 0 until shown) {
            val ry = y + headRowH + r * rowH
            c.drawLine(m, ry, m + inner, ry, grid)
            val l = d.lines.getOrNull(r) ?: continue
            val tp = paint(25f, ink)
            val tc = paint(25f, ink, false, Paint.Align.CENTER)
            var x = m
            val art = (l.article.ifBlank { l.name }).uppercase()
            val cells = listOf(
                l.sr.toString(), art, l.colour.uppercase(), l.size, l.qty.toString(),
                Fmt.indian(l.mrp), Fmt.indian(l.rate), Fmt.indian(l.amount)
            )
            for (i in cols.indices) {
                if (i == 1 || i == 2) c.drawText(fit(cells[i], tp, cols[i] - 16f), x + 10f, ry + 32f, tp)
                else c.drawText(fit(cells[i], tc, cols[i] - 8f), x + cols[i] / 2f, ry + 32f, tc)
                x += cols[i]
            }
        }
        c.drawRoundRect(tb, 12f, 12f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(190, 190, 190); style = Paint.Style.STROKE; strokeWidth = 2f })
        y += tableH + 16f

        // ---------------- tagline art (left) + totals (right)
        val totW = 470f
        val tx = W - m - totW
        val trows = ArrayList<Pair<String, String>>()
        trows.add("Total MRP" to Fmt.moneyG(cur, d.totalMrp))
        trows.add("Total Discount" to Fmt.moneyG(cur, d.totalDiscount))
        trows.add("Sub Total (After Discount)" to Fmt.moneyG(cur, d.subTotal))
        trows.add((if (d.taxPercent > 0.0) "Tax (GST " + BillMath.plain(d.taxPercent) + "%)" else "Tax (GST)") to Fmt.moneyG(cur, d.taxAmount))
        val trH = 50f
        val grandH = 68f
        val totH = trH * trows.size + grandH
        if (t.showTagline && d.shop.tagline.isNotBlank()) {
            val tp = paint(46f, ink, true)
            tp.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
            val lines = wrap(d.shop.tagline.uppercase(), tp, tx - m - 40f).take(4)
            c.save()
            c.rotate(-6f, m + 20f, y + totH / 2f)
            var ty = y + totH / 2f - (lines.size * 52f) / 2f + 40f
            for ((i, l) in lines.withIndex()) {
                tp.color = if (i == 1 || (lines.size == 1)) t.accent else ink
                c.drawText(l, m + 26f, ty, tp); ty += 52f
            }
            c.drawRoundRect(RectF(m + 26f, ty - 30f, m + 190f, ty - 24f), 3f, 3f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.accent })
            c.restore()
        }
        val tbox = RectF(tx, y, tx + totW, y + totH)
        c.drawRoundRect(tbox, 12f, 12f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(Color.WHITE, 238); style = Paint.Style.FILL })
        for ((i, row) in trows.withIndex()) {
            val ry = y + i * trH
            if (i % 2 == 0) {
                val lab = Path().apply {
                    addRoundRect(RectF(tx, ry, tx + totW * 0.58f, ry + trH),
                        floatArrayOf(if (i == 0) 12f else 0f, if (i == 0) 12f else 0f, 0f, 0f, 0f, 0f, 0f, 0f), Path.Direction.CW)
                }
                c.drawPath(lab, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(t.tint, 255); style = Paint.Style.FILL })
            }
            c.drawLine(tx, ry, tx + totW, ry, grid)
            val lp = paint(25f, ink, true)
            c.drawText(fit(row.first, lp, totW * 0.58f - 20f), tx + 14f, ry + 34f, lp)
            c.drawText(row.second, tx + totW - 14f, ry + 34f, paint(27f, ink, true, Paint.Align.RIGHT))
        }
        val gy = y + trH * trows.size
        val grandBg = Path().apply { addRoundRect(RectF(tx, gy, tx + totW, gy + grandH), floatArrayOf(0f, 0f, 0f, 0f, 12f, 12f, 12f, 12f), Path.Direction.CW) }
        c.drawPath(grandBg, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.accent; style = Paint.Style.FILL })
        c.drawText("Grand Total", tx + 16f, gy + 46f, paint(33f, Color.WHITE, true))
        c.drawText(Fmt.moneyG(cur, d.grandTotal), tx + totW - 16f, gy + 46f, paint(36f, Color.WHITE, true, Paint.Align.RIGHT))
        c.drawRoundRect(tbox, 12f, 12f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(190, 190, 190); style = Paint.Style.STROKE; strokeWidth = 2f })
        y += totH + 18f

        // ---------------- payment details + notes
        val payW = if (t.showNotes && d.shop.notes.isNotEmpty()) 440f else inner
        val notesW = inner - payW - 14f
        val np = paint(23f, ink)
        val noteLines = ArrayList<String>()
        if (t.showNotes) for (n in d.shop.notes) noteLines.addAll(wrap(n, np, notesW - 70f).mapIndexed { i, s -> if (i == 0) "• $s" else "   $s" })
        val payH = 46f + 3 * 52f + 14f
        val notesH = 46f + noteLines.size * 31f + 22f
        val blockH = maxOf(payH, if (t.showNotes && d.shop.notes.isNotEmpty()) notesH else 0f)
        card(c, m, y, payW, blockH, "Payment Details", ICON_CARD, t)
        fun payRow(yy: Float, icon: String, key: String, value: String, color: Int) {
            drawIcon(c, icon, m + 14f, yy - 24f, 26f, t.accent2)
            val kp = paint(25f, ink, true)
            if (kp.measureText(key) > 170f) kp.textSize = 25f * 170f / kp.measureText(key)   // label stays inside its column
            c.drawText(key, m + 52f, yy, kp)
            c.drawText(":", m + 232f, yy, paint(25f, ink))
            val vp = paint(26f, color, color != ink)
            val vx = m + 252f
            val vw = payW - 252f - 14f
            c.drawRoundRect(RectF(vx - 6f, yy - 32f, vx + vw, yy + 10f), 8f, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.rgb(244, 244, 244); style = Paint.Style.FILL })
            c.drawText(fit(value, vp, vw - 12f), vx + 4f, yy, vp)
        }
        payRow(y + 46f + 40f, ICON_CARD, "Payment Mode", d.method, ink)
        payRow(y + 46f + 92f, ICON_CART, "Amount Paid", Fmt.moneyG(cur, d.amountPaid), ink)
        payRow(y + 46f + 144f, ICON_DOC, "Balance", Fmt.moneyG(cur, d.balance), if (d.balance <= 0.0) Color.rgb(30, 130, 60) else t.accent)
        if (t.showNotes && d.shop.notes.isNotEmpty()) {
            val nx = m + payW + 14f
            card(c, nx, y, notesW, blockH, "Notes", ICON_DOC, t)
            var ny = y + 46f + 34f
            for (l in noteLines) { c.drawText(l, nx + 18f, ny, np); ny += 31f }
        }
        y += blockH + 22f

        // ---------------- thank you
        if (t.showThanks) {
            val th = d.shop.thanks.ifBlank { "Thank you!" }
            val tp = paint(64f, t.accent, true, Paint.Align.CENTER)
            tp.typeface = Typeface.create("cursive", Typeface.BOLD_ITALIC)
            val tw = tp.measureText(th)
            val lineP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.accent; strokeWidth = 3f }
            val ly = y + 40f
            val gap = 22f
            val leftEnd = W / 2f - tw / 2f - gap
            val rightStart = W / 2f + tw / 2f + gap
            if (leftEnd - 60f > 40f) {
                c.drawLine(60f, ly, leftEnd, ly, lineP)
                c.drawLine(rightStart, ly, W - 60f, ly, lineP)
            }
            c.drawText(th, W / 2f, y + 62f, tp)
            y += 86f
            val vp = paint(27f, ink, false, Paint.Align.CENTER)
            vp.letterSpacing = 0.45f
            c.drawText("VISIT AGAIN.", W / 2f, y + 20f, vp)
            y += 44f
        }
        if (t.showFooter && d.shop.footer.isNotBlank()) {
            val fp = paint(23f, grey, false, Paint.Align.CENTER)
            for (l in wrap(d.shop.footer, fp, inner - 40f)) { c.drawText(l, W / 2f, y + 22f, fp); y += 30f }
        }
        return y + 28f
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
        if (d.shop.gstin.isNotBlank()) { c.drawText("GSTIN: " + d.shop.gstin, w / 2f, y + 22f, cen); y += 28f }
        rule()

        lr("Bill No: " + d.billNo, "", bold)
        c.drawText(Fmt.dateTime(d.dateMillis), m, y + 22f, norm); y += 30f
        val cust = d.customerName.ifBlank { "Walk-in Customer" } + (if (d.customerMobile.isNotBlank()) " (" + d.customerMobile + ")" else "")
        for (l in wrap("Customer: $cust", norm, w - 2 * m)) { c.drawText(l, m, y + 22f, norm); y += 29f }
        if (d.salesPerson.isNotBlank()) { c.drawText("Sales Person: " + d.salesPerson, m, y + 22f, norm); y += 29f }
        rule()

        for (l in d.lines) {
            val title = (l.article.ifBlank { l.name }).uppercase() +
                (if (l.article.isNotBlank() && l.name.isNotBlank() && !l.name.equals(l.article, true)) "  " + l.name else "")
            for ((i, tl) in wrap("${l.sr}. $title", bold, w - 2 * m).withIndex()) { c.drawText(tl, m, y + 22f, bold); y += 29f }
            lr("   " + l.colour.uppercase() + " / " + l.size + "   " + l.qty + " x " + Fmt.indian(l.rate), Fmt.indian(l.amount))
            if (l.mrp > l.rate) { c.drawText("   MRP " + Fmt.indian(l.mrp), m, y + 20f, paint(21f, Color.rgb(60, 60, 60))); y += 26f }
        }
        rule()
        lr("Total MRP", Fmt.moneyG(cur, d.totalMrp))
        lr("Total Discount", "-" + Fmt.moneyG(cur, d.totalDiscount))
        lr("Sub Total", Fmt.moneyG(cur, d.subTotal))
        if (d.taxPercent > 0.0 || d.taxAmount > 0.0) lr("GST (" + BillMath.plain(d.taxPercent) + "%)", Fmt.moneyG(cur, d.taxAmount))
        rule()
        val gp = paint(32f, ink, true)
        val gr = paint(34f, ink, true, Paint.Align.RIGHT)
        c.drawText("GRAND TOTAL", m, y + 30f, gp)
        c.drawText(Fmt.moneyG(cur, d.grandTotal), w - m, y + 30f, gr); y += 42f
        rule()
        lr("Payment Mode", d.method)
        lr("Amount Paid", Fmt.moneyG(cur, d.amountPaid))
        lr("Balance", Fmt.moneyG(cur, d.balance), bold, rightBold)

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
