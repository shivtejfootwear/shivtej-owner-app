package com.shivtej.owner.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shop details exactly as they were when a bill was made (kept inside every bill, so old bills never change). */
data class ShopInfo(
    val name: String = "Shivtej Footwear",
    val tagline: String = "FOOTWEAR FOR EVERY STEP",
    val address: String = "",
    val phone: String = "",
    val email: String = "",
    val website: String = "",
    val instagram: String = "",
    val whatsapp: String = "",
    val gstin: String = "",
    val footer: String = "",
    val thanks: String = "Thank you!",
    val notes: List<String> = emptyList(),
    val logoPath: String = "",
    val currency: String = "₹"
)

data class RLine(
    val sr: Int,
    val article: String,
    val name: String,
    val colour: String,
    val size: String,
    val qty: Int,
    val mrp: Double,
    val rate: Double
) {
    val amount: Double get() = BillMath.round2(rate * qty)
}

/** Everything a printed / shared bill shows. Built from a saved bill; both the invoice picture and the 80mm printer use it. */
data class ReceiptData(
    val shop: ShopInfo,
    val billNo: String,
    val dateMillis: Long,
    val customerName: String,
    val customerMobile: String,
    val customerAddress: String,
    val salesPerson: String,
    val lines: List<RLine>,
    val totalMrp: Double,
    val totalDiscount: Double,
    val subTotal: Double,
    val taxPercent: Double,
    val taxAmount: Double,
    val grandTotal: Double,
    val method: String,
    val amountPaid: Double,
    val balance: Double
)

object Fmt {
    fun money(cur: String, v: Double): String = cur + BillMath.plain(v)

    /** Indian digit grouping: 1,920  12,345  1,23,456  (paise only when there are some). */
    fun indian(v: Double): String {
        val neg = v < 0.0
        val a = Math.abs(v)
        val whole = Math.floor(a + 0.0000001).toLong()
        val paise = Math.round((a - whole) * 100.0).toInt().let { if (it >= 100) 0 else it }
        val w = if (paise == 0 && Math.round(a * 100.0) >= (whole + 1) * 100L) whole + 1 else whole
        val digits = w.toString()
        val grouped = if (digits.length <= 3) digits else {
            val last3 = digits.substring(digits.length - 3)
            var rest = digits.substring(0, digits.length - 3)
            val parts = ArrayList<String>()
            while (rest.length > 2) { parts.add(0, rest.substring(rest.length - 2)); rest = rest.substring(0, rest.length - 2) }
            if (rest.isNotEmpty()) parts.add(0, rest)
            parts.joinToString(",") + "," + last3
        }
        val tail = if (paise == 0) "" else "." + paise.toString().padStart(2, '0')
        return (if (neg && (w != 0L || paise != 0)) "-" else "") + grouped + tail
    }

    fun moneyG(cur: String, v: Double): String = cur + indian(v)

    fun dateTime(ms: Long): String =
        SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.ENGLISH).format(Date(ms)).replace("AM", "am").replace("PM", "pm")

    fun dateOnly(ms: Long): String = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).format(Date(ms))
    fun timeOnly(ms: Long): String = SimpleDateFormat("hh:mm a", Locale.ENGLISH).format(Date(ms)).replace("AM", "am").replace("PM", "pm")

    /** Word wrap for a fixed number of columns (printer text mode). */
    fun wrap(text: String, cols: Int): List<String> {
        val out = ArrayList<String>()
        for (para in text.split("\n")) {
            var line = ""
            for (w in para.trim().split(" ").filter { it.isNotEmpty() }) {
                var word = w
                while (word.length > cols) {
                    if (line.isNotEmpty()) { out.add(line); line = "" }
                    out.add(word.substring(0, cols)); word = word.substring(cols)
                }
                line = when {
                    line.isEmpty() -> word
                    line.length + 1 + word.length <= cols -> "$line $word"
                    else -> { out.add(line); word }
                }
            }
            out.add(line)
        }
        return out
    }
}

/**
 * Plain-text 80mm receipt (48 characters per line) for printers / modes where a picture is not wanted.
 * Text mode can only print simple characters, so the rupee sign is printed as "Rs.".
 */
object ThermalText {
    private fun money(v: Double) = BillMath.plain(v)

    private fun center(s: String, cols: Int): String {
        val t = if (s.length > cols) s.substring(0, cols) else s
        val pad = (cols - t.length) / 2
        return " ".repeat(pad) + t
    }

    private fun lr(left: String, right: String, cols: Int): String {
        val l = if (left.length + right.length + 1 > cols) left.substring(0, (cols - right.length - 1).coerceAtLeast(0)) else left
        return l + " ".repeat((cols - l.length - right.length).coerceAtLeast(1)) + right
    }

    fun build(d: ReceiptData, cols: Int = 48): List<String> {
        val out = ArrayList<String>()
        val dash = "-".repeat(cols)
        val s = d.shop
        out.add(center(s.name.uppercase(), cols))
        if (s.tagline.isNotBlank()) out.add(center(s.tagline, cols))
        if (s.address.isNotBlank()) Fmt.wrap(s.address, cols).forEach { out.add(center(it, cols)) }
        if (s.phone.isNotBlank()) out.add(center("Ph: " + s.phone, cols))
        if (s.gstin.isNotBlank()) out.add(center("GSTIN: " + s.gstin, cols))
        out.add(dash)
        out.add(lr("Bill No: " + d.billNo, "", cols))
        out.add(Fmt.dateTime(d.dateMillis))
        val cust = (d.customerName.ifBlank { "Walk-in Customer" }) + (if (d.customerMobile.isNotBlank()) " (" + d.customerMobile + ")" else "")
        Fmt.wrap("Customer: $cust", cols).forEach { out.add(it) }
        if (d.salesPerson.isNotBlank()) out.add("Sales Person: " + d.salesPerson)
        out.add(dash)
        for (l in d.lines) {
            val title = (l.article.ifBlank { l.name }).uppercase() + (if (l.article.isNotBlank() && l.name.isNotBlank() && !l.name.equals(l.article, true)) " " + l.name else "")
            Fmt.wrap("${l.sr}. $title", cols).forEach { out.add(it) }
            val detail = "   " + l.colour.uppercase() + " / " + l.size + "  " + l.qty + " x " + money(l.rate)
            out.add(lr(detail, money(l.amount), cols))
            if (l.mrp > l.rate) out.add("   MRP " + money(l.mrp))
        }
        out.add(dash)
        out.add(lr("Total MRP", money(d.totalMrp), cols))
        out.add(lr("Total Discount", "-" + money(d.totalDiscount), cols))
        out.add(lr("Sub Total", money(d.subTotal), cols))
        if (d.taxPercent > 0.0 || d.taxAmount > 0.0) out.add(lr("GST (" + BillMath.plain(d.taxPercent) + "%)", money(d.taxAmount), cols))
        out.add(dash)
        out.add(lr("GRAND TOTAL (Rs.)", money(d.grandTotal), cols))
        out.add(dash)
        out.add(lr("Payment Mode", d.method, cols))
        out.add(lr("Amount Paid", money(d.amountPaid), cols))
        out.add(lr("Balance", money(d.balance), cols))
        if (d.shop.notes.isNotEmpty()) {
            out.add(dash)
            d.shop.notes.forEach { n -> Fmt.wrap("* $n", cols).forEach { out.add(it) } }
        }
        out.add(dash)
        out.add(center(s.thanks.ifBlank { "Thank you!" }, cols))
        out.add(center("VISIT AGAIN", cols))
        if (s.footer.isNotBlank()) Fmt.wrap(s.footer, cols).forEach { out.add(center(it, cols)) }
        return out
    }
}
