package com.shivtej.owner.util

import com.shivtej.owner.data.*
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Everything the assistant may read. All of it comes from this phone's own database.
 * Money is formatted by the caller ([money]) so the currency and separators match the rest of the app.
 */
class AssistantData(
    val products: List<Product>,
    val bills: List<BillE>,
    val orders: List<OrderE>,
    val payments: List<CustomerPaymentE>,
    val customerNames: Map<String, String>,        // normalised mobile -> saved name
    val supplierOwed: List<Pair<String, Double>>,  // supplier name -> still to pay (only amounts above 0)
    val openTasks: List<TaskE>,
    val lowThreshold: Int,
    val money: (Double) -> String,
    val now: Long,
    val webOrders: List<Ledger.WebOrder> = emptyList(),        // the same orders as `orders`, for the udhaar ledger
    val ledgerReturns: List<Ledger.LedgerReturn> = emptyList() // returns and exchanges, for the udhaar ledger
)

/**
 * The offline assistant. It understands English, Hinglish (Roman Hindi) and some Hindi words,
 * and answers ONLY from [AssistantData]. It never needs internet. Pure Kotlin, so it can be unit-tested on a JVM.
 */
object Assistant {

    private class StockRow(val p: Product, val colour: String, val size: String, val qty: Int)

    private fun startOfDay(offsetDays: Int): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, offsetDays)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun dt(ms: Long): String = SimpleDateFormat("dd MMM, hh:mm a", Locale.ENGLISH).format(Date(ms))

    private fun rows(d: AssistantData): List<StockRow> =
        d.products.flatMap { p -> p.variants.flatMap { v -> v.sizes.map { StockRow(p, v.colour, it.size, it.qty) } } }

    private fun StockRow.line(): String = "• ${p.article} ${p.name} - $colour / size $size: $qty pcs"

    private fun velocityKey(productId: String, colour: String, size: String) = "$productId|${colour.lowercase()}|$size"

    private fun soldSince(d: AssistantData, since: Long): List<BillItem> =
        d.bills.filter { it.createdAt >= since }.flatMap { parseBillItems(it.itemsJson) }

    private fun salesIn(d: AssistantData, from: Long, until: Long): Pair<Double, Int> {
        val list = d.bills.filter { it.createdAt >= from && it.createdAt < until }
        return list.sumOf { it.total } to list.size
    }

    /** Answers one question from the phone's data. */
    fun offline(question: String, d: AssistantData): String {
        val t = question.lowercase().trim()
        val words = t.split(Regex("[^a-z0-9\\u0900-\\u097F]+")).filter { it.isNotEmpty() }
        fun has(vararg k: String) = k.any { t.contains(it) }
        fun hasWord(vararg k: String) = k.any { it in words }
        val byArticle = productByToken(words, d)

        return when {
            has("help", "madad", "what can", "kya pooch", "kya puch", "मदद") -> helpText()
            has("task", "reminder", "yaad", "kaam", "याद", "काम") -> tasksText(d)
            has("supplier", "suplier", "vendor", "सप्लायर") &&
                has("due", "baaki", "owe", "pay", "bill", "dena", "बाकी", "देना") -> supplierText(d)
            has("udhaar", "udhar", "udhari", "due", "baaki", "outstanding", "ledger", "credit", "dues", "उधार", "बाकी") ->
                customerDuesText(t, d)
            has("compare", "tulna", "pichle", "last week", "7 din", "7 days", "hafte", "हफ्ते") -> compareText(d)
            has("konsa", "kaunsa", "kaun sa", "kya bika", "kya kya bika", "kitne bike", "bika", "बिका") -> todayItemsText(d)
            byArticle != null -> stockOfProductText(words, byArticle, d.money)
            has("reorder", "restock", "mangwa", "order karna", "kya lena", "khareed", "buy", "मंगवा") -> reorderText(d)
            hasWord("out") || has("out of stock", "khatam", "stock out", "zero stock", "nahi hai", "खत्म") -> outText(d)
            hasWord("low", "kam", "less", "कम") || has("kam stock", "low stock") -> lowText(d)
            hasWord("best", "top", "selling", "सबसे") || has("zyada bik", "most sold") -> bestText(d)
            has("pending", "naya order", "new order", "order") -> ordersText(d)
            has("aaj", "today", "sale", "bikri", "bill", "total", "आज", "बिक्री") -> todayText(d)
            else -> searchText(words, d)
        }
    }

    fun helpText(): String =
        "I answer from this phone's data, even without internet:\n" +
            "• Today's sales, last 7 days compared with the 7 days before\n" +
            "• What sold today, best sellers (30 days)\n" +
            "• Low stock, out of stock, what to reorder\n" +
            "• Stock of an article: type its number, for example 6100 or 6100 size 8\n" +
            "• Customer dues (udhaar): type 'udhaar' for all, or a name or mobile number\n" +
            "• Supplier dues, pending website orders\n" +
            "• Open tasks and reminders"

    private fun productByToken(words: List<String>, d: AssistantData): Product? =
        d.products.firstOrNull { p -> words.any { w -> w.length >= 3 && p.article.lowercase() == w } }

    private fun stockOfProductText(words: List<String>, p: Product, money: (Double) -> String): String {
        val wantedSize: String? = run {
            val i = words.indexOf("size")
            if (i >= 0 && i + 1 < words.size) words[i + 1]
            else words.firstOrNull { w -> p.variants.any { v -> v.sizes.any { it.size.equals(w, true) } } }
        }
        val lines = ArrayList<String>()
        for (v in p.variants) {
            val sizes = v.sizes.filter { wantedSize == null || it.size.equals(wantedSize, true) }
            if (sizes.isEmpty()) continue
            lines.add("${v.colour}: " + sizes.joinToString(", ") { "${it.size} = ${it.qty}" })
        }
        val head = "${p.article} ${p.name} (price ${money(p.price)})" +
            if (wantedSize != null) " - size $wantedSize" else ""
        return if (lines.isEmpty()) "$head\nNo stock entry found for that size." else "$head\n" + lines.joinToString("\n")
    }

    private fun tasksText(d: AssistantData): String {
        val open = d.openTasks.filter { !it.done }
            .sortedWith(compareBy<TaskE>({ if (it.dueAt == 0L) 1 else 0 }, { it.dueAt }))
        if (open.isEmpty()) return "You have no open tasks. Open Tasks & Reminders from the menu and tap + to add one."
        val overdue = open.count { it.dueAt in 1 until d.now }
        val head = "Open tasks: ${open.size}" + if (overdue > 0) " ($overdue overdue)" else ""
        return head + "\n" + open.take(10).joinToString("\n") {
            "• " + it.title + if (it.dueAt > 0L) " - due ${dt(it.dueAt)}" else " (no date)"
        } + if (open.size > 10) "\n...and ${open.size - 10} more." else ""
    }

    private fun customerDuesText(t: String, d: AssistantData): String {
        val sums = Ledger.summaries(d.bills, d.payments, d.webOrders, d.ledgerReturns)
        val mobileTyped = Regex("\\d{10}").find(t.filter { it.isDigit() || it == ' ' }.replace(" ", ""))?.value
        val mobileByName = d.customerNames.entries.firstOrNull { (_, name) -> name.length >= 3 && t.contains(name.lowercase()) }?.key
        val mobile = mobileTyped ?: mobileByName
        if (mobile != null) {
            val s = sums[mobile] ?: Ledger.summaryOf(mobile, d.bills, d.payments, d.webOrders, d.ledgerReturns)
            val name = d.customerNames[mobile] ?: "Customer"
            val tail = if (s.advance > 0) "\nAdvance with the shop: ${d.money(s.advance)}" else ""
            return "$name: billed ${d.money(s.billed)}, received ${d.money(s.payments)}, due ${d.money(s.outstanding)}$tail\n" +
                "Open Customers > this customer > Account ledger for the full history."
        }
        val owing = sums.values.filter { it.outstanding > 0.0 }.sortedByDescending { it.outstanding }
        if (owing.isEmpty()) return "No customer owes money right now."
        val total = owing.sumOf { it.outstanding }
        return "Total due from ${owing.size} customer(s): ${d.money(total)}\n" +
            owing.take(10).joinToString("\n") { "• ${d.customerNames[it.mobile] ?: "Customer"}: ${d.money(it.outstanding)}" } +
            if (owing.size > 10) "\n...and ${owing.size - 10} more." else ""
    }

    private fun supplierText(d: AssistantData): String {
        val owed = d.supplierOwed.sortedByDescending { it.second }
        if (owed.isEmpty()) return "No supplier payment is pending."
        return "Total still to pay suppliers: ${d.money(owed.sumOf { it.second })}\n" +
            owed.take(10).joinToString("\n") { "• ${it.first}: ${d.money(it.second)}" }
    }

    private fun compareText(d: AssistantData): String {
        val (last, lastN) = salesIn(d, startOfDay(-6), Long.MAX_VALUE)
        val (prev, _) = salesIn(d, startOfDay(-13), startOfDay(-6))
        val change = if (prev > 0.0) {
            val pct = (last - prev) / prev * 100.0
            "Change: " + (if (pct >= 0) "+" else "") + String.format(Locale.ENGLISH, "%.0f", pct) + "%"
        } else "No POS sales in the 7 days before, so no change to compare."
        return "Last 7 days (POS): ${d.money(last)} from $lastN bill(s)\nThe 7 days before: ${d.money(prev)}\n$change"
    }

    private fun todayItemsText(d: AssistantData): String {
        val items = soldSince(d, startOfDay(0)).groupBy { (it.article.ifBlank { it.name }) }
            .map { (k, v) -> Triple(k, v.sumOf { it.qty }, v.sumOf { it.price * it.qty }) }
            .sortedByDescending { it.second }
        if (items.isEmpty()) return "Nothing has been sold in the shop today yet."
        return "Sold today:\n" + items.take(15).joinToString("\n") { "• ${it.first}: ${it.second} pcs (${d.money(BillMath.round2(it.third))})" }
    }

    private fun reorderText(d: AssistantData): String {
        val sold30 = soldSince(d, startOfDay(-29)).groupBy { velocityKey(it.productId, it.colour, it.size) }
            .mapValues { e -> e.value.sumOf { it.qty } }
        val list = rows(d).filter { it.qty <= d.lowThreshold }
            .sortedByDescending { sold30[velocityKey(it.p.id, it.colour, it.size)] ?: 0 }
        if (list.isEmpty()) return "Nothing needs reordering right now."
        return "Reorder first (fast sellers in the last 30 days):\n" + list.take(15).joinToString("\n") {
            it.line() + "  (sold ${sold30[velocityKey(it.p.id, it.colour, it.size)] ?: 0} in 30 days)"
        }
    }

    private fun outText(d: AssistantData): String {
        val l = rows(d).filter { it.qty <= 0 }
        if (l.isEmpty()) return "Nothing is out of stock."
        return "${l.size} size(s) are out of stock:\n" + l.take(25).joinToString("\n") { it.line() } +
            if (l.size > 25) "\n...and ${l.size - 25} more." else ""
    }

    private fun lowText(d: AssistantData): String {
        val l = rows(d).filter { it.qty in 1..d.lowThreshold }.sortedBy { it.qty }
        if (l.isEmpty()) return "No sizes are low (limit ${d.lowThreshold})."
        return "${l.size} size(s) are low (${d.lowThreshold} or fewer):\n" + l.take(25).joinToString("\n") { it.line() } +
            if (l.size > 25) "\n...and ${l.size - 25} more." else ""
    }

    private fun bestText(d: AssistantData): String {
        val top = soldSince(d, startOfDay(-29)).groupBy { it.article + " " + it.name }
            .map { it.key to it.value.sumOf { x -> x.qty } }
            .sortedByDescending { it.second }.take(8)
        if (top.isEmpty()) return "No POS sales in the last 30 days yet."
        return "Best sellers (30 days):\n" + top.joinToString("\n") { "• ${it.first.trim()}: ${it.second} pcs" }
    }

    private fun ordersText(d: AssistantData): String {
        if (d.orders.isEmpty()) return "There are no website orders yet."
        val news = d.orders.filter { it.orderStatus == "New" }
        val ready = d.orders.count { it.orderStatus == "Confirmed" || it.orderStatus == "Packed" }
        return "${news.size} new order(s) waiting for confirmation, $ready confirmed or packed." +
            if (news.isNotEmpty()) "\n" + news.take(8).joinToString("\n") { "• #${it.id} - ${d.money(it.total)}" } else ""
    }

    private fun todayText(d: AssistantData): String {
        val from = startOfDay(0)
        val (pos, posN) = salesIn(d, from, Long.MAX_VALUE)
        val online = d.orders.filter { it.createdAt >= from && it.orderStatus != "Cancelled" }
        return "Today: $posN POS bill(s) = ${d.money(pos)}, ${online.size} online order(s) = ${d.money(online.sumOf { it.total })}."
    }

    private fun searchText(words: List<String>, d: AssistantData): String {
        val found = d.products.filter { p ->
            words.any { w -> w.length >= 2 && (p.name.lowercase().contains(w) || p.article.lowercase().contains(w) ||
                p.brand.lowercase().contains(w) || p.variants.any { it.colour.lowercase().contains(w) }) }
        }.take(5)
        if (found.isEmpty()) return "I could not find that. Type \"help\" to see what I can answer."
        return found.joinToString("\n\n") { p ->
            "${p.article} ${p.name} (${d.money(p.price)}) - total ${p.totalStock}\n" +
                p.variants.joinToString("\n") { v -> "  ${v.colour}: " + v.sizes.joinToString(", ") { "${it.size}=${it.qty}" } }
        }
    }

    /**
     * A short summary of the business for the optional online AI. It has totals and the top few names only:
     * no customer phone numbers and no full customer list.
     */
    fun summaryForAi(d: AssistantData): String {
        val sb = StringBuilder()
        val from = startOfDay(0)
        val (pos, posN) = salesIn(d, from, Long.MAX_VALUE)
        val online = d.orders.filter { it.createdAt >= from && it.orderStatus != "Cancelled" }
        sb.append("Now: ${dt(d.now)}\n")
        sb.append("Today: $posN POS bills = ${d.money(pos)}; ${online.size} online orders = ${d.money(online.sumOf { it.total })}\n")
        val (last, _) = salesIn(d, startOfDay(-6), Long.MAX_VALUE)
        val (prev, _) = salesIn(d, startOfDay(-13), startOfDay(-6))
        sb.append("Last 7 days POS sales: ${d.money(last)}; the 7 days before: ${d.money(prev)}\n")
        val all = rows(d)
        sb.append("Stock: ${all.count { it.qty <= 0 }} size(s) out of stock; ${all.count { it.qty in 1..d.lowThreshold }} size(s) low (limit ${d.lowThreshold}).\n")
        val lowList = all.filter { it.qty <= d.lowThreshold }.sortedBy { it.qty }.take(15)
        if (lowList.isNotEmpty()) sb.append("Low or out items: " + lowList.joinToString("; ") { "${it.p.article} ${it.p.name} ${it.colour} size ${it.size} = ${it.qty}" } + "\n")
        val owing = Ledger.summaries(d.bills, d.payments, d.webOrders, d.ledgerReturns).values.filter { it.outstanding > 0.0 }.sortedByDescending { it.outstanding }
        sb.append("Customers who owe money: ${owing.size}; total due ${d.money(owing.sumOf { it.outstanding })}\n")
        if (owing.isNotEmpty()) sb.append("Top dues: " + owing.take(10).joinToString("; ") { "${d.customerNames[it.mobile] ?: "Customer"} ${d.money(it.outstanding)}" } + "\n")
        sb.append("Suppliers still to pay: ${d.money(d.supplierOwed.sumOf { it.second })}\n")
        sb.append("Website orders waiting confirmation: ${d.orders.count { it.orderStatus == "New" }}\n")
        val tasks = d.openTasks.filter { !it.done }.take(10)
        if (tasks.isNotEmpty()) sb.append("Open tasks: " + tasks.joinToString("; ") { it.title + if (it.dueAt > 0) " (due ${dt(it.dueAt)})" else "" } + "\n")
        return sb.toString().take(3500)
    }
}
