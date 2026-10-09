package com.shivtej.owner.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** QR codes for the bill (website / Instagram). Made on the phone, no internet needed. */
object Qr {
    fun bitmap(text: String, size: Int, dark: Int = Color.BLACK, light: Int = Color.WHITE): Bitmap? {
        if (text.isBlank() || size < 21) return null
        return try {
            val hints = HashMap<EncodeHintType, Any>()
            hints[EncodeHintType.MARGIN] = 1
            hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.M
            val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
            val w = m.width
            val h = m.height
            val px = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) px[y * w + x] = if (m.get(x, y)) dark else light
            Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        } catch (e: Exception) { null }
    }
}
