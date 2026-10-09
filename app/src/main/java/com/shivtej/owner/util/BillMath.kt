package com.shivtej.owner.util

import java.util.Locale

/** One billed line, only the numbers that matter for money. */
data class MathLine(val mrp: Double, val rate: Double, val qty: Int)

data class BillTotals(
    val totalMrp: Double,
    val totalDiscount: Double,
    val subTotal: Double,       // after discount = what the customer pays for the goods
    val taxPercent: Double,
    val taxAmount: Double,
    val grandTotal: Double
)

/**
 * All billing arithmetic in ONE place (no Android code in here, so it is unit-tested on a plain JVM).
 *
 *   Discount = MRP - Selling rate           (never negative)
 *   Sub total = sum(rate * qty)
 *   Total MRP = Total discount + Sub total
 *   Tax       = Sub total * tax% / 100
 *   Grand     = Sub total + Tax
 *
 * Stock is never touched by this file.
 */
object BillMath {
    fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0

    /** The MRP that is printed for a line: never lower than the selling rate, so the discount can not be negative. */
    fun lineMrp(mrp: Double, rate: Double): Double = if (mrp > rate) mrp else rate

    fun lineDiscount(mrp: Double, rate: Double, qty: Int): Double = round2((lineMrp(mrp, rate) - rate) * qty)

    fun compute(lines: List<MathLine>, taxPercent: Double): BillTotals {
        var mrp = 0.0
        var sub = 0.0
        for (l in lines) {
            mrp += lineMrp(l.mrp, l.rate) * l.qty
            sub += l.rate * l.qty
        }
        val totalMrp = round2(mrp)
        val subTotal = round2(sub)
        val tp = if (taxPercent < 0.0) 0.0 else taxPercent
        val tax = round2(subTotal * tp / 100.0)
        return BillTotals(
            totalMrp = totalMrp,
            totalDiscount = round2(totalMrp - subTotal),
            subTotal = subTotal,
            taxPercent = tp,
            taxAmount = tax,
            grandTotal = round2(subTotal + tax)
        )
    }

    /** Amount still to be paid. Paying more than the total leaves a balance of 0. */
    fun balance(grand: Double, paid: Double): Double {
        val b = round2(grand - paid)
        return if (b < 0.0) 0.0 else b
    }

    /** A typed amount, tolerant of spaces, commas and a currency sign. Returns null when it is not a number. */
    fun parseAmount(text: String): Double? {
        val cleaned = text.trim().replace(",", "").replace("₹", "").replace("Rs.", "").replace("Rs", "").trim()
        if (cleaned.isEmpty()) return null
        val v = cleaned.toDoubleOrNull() ?: return null
        if (v.isNaN() || v.isInfinite()) return null
        return v
    }

    /** "1,234" style number without a currency sign: whole numbers have no decimals. */
    fun plain(v: Double): String =
        if (Math.abs(v - Math.rint(v)) < 0.005) Math.rint(v).toLong().toString() else String.format(Locale.US, "%.2f", v)

    // ------------------------------------------------------------------ phone numbers
    /** Digits only; a longer number (+91 ...) keeps its last 10 digits. */
    fun normMobile(raw: String): String {
        val d = raw.filter { it in '0'..'9' }
        return if (d.length > 10) d.takeLast(10) else d
    }

    fun isValidMobile(raw: String): Boolean = normMobile(raw).length == 10
}
