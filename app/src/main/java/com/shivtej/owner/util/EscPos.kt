package com.shivtej.owner.util

import java.io.ByteArrayOutputStream

/**
 * ESC/POS command builder for 80mm thermal receipt printers (ATPOS AT-301 and compatible).
 * Pure Kotlin (no Android classes), so it is tested on a normal JVM.
 */
object EscPos {
    /** 80mm paper at 203 dpi = 576 printable dots = 72 bytes per raster row. */
    const val DOTS_80MM = 576

    val INIT = byteArrayOf(0x1B, 0x40)                               // ESC @  reset
    fun align(n: Int) = byteArrayOf(0x1B, 0x61, n.toByte())          // ESC a  0 left, 1 centre, 2 right
    fun bold(on: Boolean) = byteArrayOf(0x1B, 0x45, if (on) 1 else 0) // ESC E
    fun size(w: Int, h: Int) = byteArrayOf(0x1D, 0x21, (((w - 1) shl 4) or (h - 1)).toByte()) // GS !  1..8
    fun feed(lines: Int) = byteArrayOf(0x1B, 0x64, lines.toByte())   // ESC d
    val CUT = byteArrayOf(0x1D, 0x56, 0x42, 0x00)                    // GS V 66 0  feed + partial cut (auto cutter)

    /** Plain ASCII only: anything else would print as garbage on a printer without the right code page. */
    fun text(s: String): ByteArray {
        val b = ByteArrayOutputStream()
        for (ch in s) {
            val c = ch.code
            when {
                ch == '\n' -> b.write(0x0A)
                c in 32..126 -> b.write(c)
                ch == '₹' -> { b.write('R'.code); b.write('s'.code); b.write('.'.code) }
                ch == '–' || ch == '—' || ch == '·' -> b.write('-'.code)
                else -> b.write('?'.code)
            }
        }
        return b.toByteArray()
    }

    fun line(s: String): ByteArray = text(s) + byteArrayOf(0x0A)

    /** Pixel -> black? Transparent pixels count as white paper. */
    private fun isBlack(argb: Int, threshold: Int): Boolean {
        val a = (argb ushr 24) and 0xFF
        if (a < 128) return false
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val bl = argb and 0xFF
        return (r * 299 + g * 587 + bl * 114) / 1000 < threshold
    }

    /**
     * Turns a picture (ARGB pixels, row by row) into GS v 0 raster commands.
     * The picture is cut into bands of [bandRows] rows, each band is its own command, so a cheap printer with a
     * small receive buffer is never fed one huge block.
     */
    fun rasterBands(argb: IntArray, width: Int, height: Int, bandRows: Int = 128, threshold: Int = 165): List<ByteArray> {
        require(argb.size >= width * height) { "pixel array too small" }
        val bytesPerRow = (width + 7) / 8
        val out = ArrayList<ByteArray>()
        var y = 0
        while (y < height) {
            val rows = minOf(bandRows, height - y)
            val data = ByteArray(8 + bytesPerRow * rows)
            data[0] = 0x1D; data[1] = 0x76; data[2] = 0x30; data[3] = 0x00            // GS v 0, normal density
            data[4] = (bytesPerRow and 0xFF).toByte(); data[5] = ((bytesPerRow shr 8) and 0xFF).toByte()
            data[6] = (rows and 0xFF).toByte(); data[7] = ((rows shr 8) and 0xFF).toByte()
            var p = 8
            for (r in 0 until rows) {
                val rowStart = (y + r) * width
                for (bx in 0 until bytesPerRow) {
                    var v = 0
                    for (bit in 0 until 8) {
                        val x = bx * 8 + bit
                        if (x < width && isBlack(argb[rowStart + x], threshold)) v = v or (0x80 shr bit)
                    }
                    data[p++] = v.toByte()
                }
            }
            out.add(data)
            y += rows
        }
        return out
    }

    /** A complete print job for a picture: reset, centre, the picture, feed, cut. */
    fun pictureJob(argb: IntArray, width: Int, height: Int, feedLines: Int = 4, cut: Boolean = true): List<ByteArray> {
        val job = ArrayList<ByteArray>()
        job.add(INIT)
        job.add(align(1))
        job.addAll(rasterBands(argb, width, height))
        job.add(feed(feedLines))
        if (cut) job.add(CUT)
        return job
    }

    /** A complete print job for plain text lines. */
    fun textJob(lines: List<String>, boldFirst: Int = 1, feedLines: Int = 4, cut: Boolean = true): List<ByteArray> {
        val job = ArrayList<ByteArray>()
        job.add(INIT)
        lines.forEachIndexed { i, l ->
            job.add(bold(i < boldFirst))
            job.add(line(l))
        }
        job.add(bold(false))
        job.add(feed(feedLines))
        if (cut) job.add(CUT)
        return job
    }
}
