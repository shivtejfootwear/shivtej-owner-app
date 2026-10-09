import com.shivtej.owner.data.*
import com.shivtej.owner.util.*

fun check(name: String, ok: Boolean) { println((if (ok) "PASS " else "FAIL ") + name) }

fun main() {
    val now = System.currentTimeMillis()
    val bills = listOf(
        BillE("b1", "SW-1", 1000.0, "Cash", "p1|6100|Sandal|Black|8|2|500.0", now - 3_600_000L, true,
            customerName = "Ram", customerMobile = "9876543210", amountPaid = 400.0),
        BillE("b2", "SW-2", 500.0, "UPI", "p2|6200|Shoe|Brown|9|1|500.0", now - 86_400_000L * 8, true,
            customerName = "Sita", customerMobile = "9123456789", amountPaid = -1.0),
        BillE("b3", "SW-3", 300.0, "Cash", "p1|6100|Sandal|Black|8|1|300.0", now - 60_000L, true,
            customerName = "Ram", customerMobile = "9876543210", amountPaid = 300.0)
    )
    val pays = listOf(
        CustomerPaymentE("pay1", "9876543210", "b1", 250.0, "Cash", "", now - 1000, false),
        CustomerPaymentE("pay2", "9876543210", "", 100.0, "UPI", "", now - 500, false, "REJECTED", "server refused")
    )

    // ---- ledger rules
    check("bill b1 remaining after 400 paid at sale and 250 later = 350", Ledger.billRemaining(bills[0], pays) == 350.0)
    val ram = Ledger.summaryOf("9876543210", bills, pays)
    check("Ram outstanding = 600 due - 250 received (rejected 100 ignored) = 350", ram.outstanding == 350.0)
    check("Ram advance = 0", ram.advance == 0.0)
    check("Sita fully paid (paid -1 = old bill, counts as paid) -> 0 due", Ledger.summaryOf("9123456789", bills, pays).outstanding == 0.0)
    val entries = Ledger.entries("9876543210", bills, pays)
    check("ledger running balance ends at outstanding", entries.last().balance == 350.0)
    check("ledger has 2 bills + 1 counted payment (rejected excluded)", entries.size == 3)
    val over = listOf(CustomerPaymentE("p9", "9876543210", "", 1000.0, "Cash", "", now, false))
    check("advance shown when payments exceed dues", Ledger.summaryOf("9876543210", bills, over).advance == 400.0)

    // ---- assistant data
    val products = listOf(
        Product("p1", "6100", "Sandal", "Brand", "Men", "", 700.0, 600.0, true, false, emptyList(),
            listOf(Variant("Black", emptyList(), listOf(SizeStock("8", 1, ""), SizeStock("9", 0, ""))))),
        Product("p2", "6200", "Shoe", "Brand", "Men", "", 900.0, 800.0, true, false, emptyList(),
            listOf(Variant("Brown", emptyList(), listOf(SizeStock("9", 5, ""), SizeStock("10", 2, "")))))
    )
    val d = AssistantData(
        products = products, bills = bills, orders = emptyList(), payments = pays,
        customerNames = mapOf("9876543210" to "Ram", "9123456789" to "Sita"),
        supplierOwed = listOf("ABC Traders" to 1200.0),
        openTasks = listOf(TaskE("t1", "Call supplier", "", now + 3_600_000L, false, 0, now, 5)),
        lowThreshold = 2, money = { "Rs " + String.format("%.2f", it) }, now = now
    )
    val a = Assistant.offline("6100 size 8", d)
    check("article 6100 size 8 answer mentions size 8 = 1", a.contains("size 8 = 1") || a.contains("8 = 1"))
    check("udhaar for Ram shows due Rs 350.00", Assistant.offline("Ram ka udhaar", d).contains("due Rs 350.00"))
    check("mobile lookup works", Assistant.offline("9876543210 udhaar", d).contains("Ram"))
    check("all dues lists total", Assistant.offline("udhaar", d).contains("Total due from 1 customer(s): Rs 350.00"))
    check("follow word does not trigger low stock", !Assistant.offline("follow up with supplier", d).startsWith("No sizes are low") &&
        !Assistant.offline("follow up with supplier", d).contains("size(s) are low"))
    check("supplier dues", Assistant.offline("supplier dues", d).contains("1200.00"))
    check("tasks lists the open task", Assistant.offline("tasks", d).contains("Call supplier"))
    check("out of stock finds size 9 of 6100", Assistant.offline("out of stock", d).contains("Sandal"))
    check("reorder answers", Assistant.offline("kya mangwana hai", d).isNotBlank())
    check("sold today lists item", Assistant.offline("konsa maal bika aaj", d).contains("Sold today"))
    check("today totals", Assistant.offline("aaj ki sale", d).contains("Today: 2 POS bill(s)"))
    check("compare answers", Assistant.offline("compare last 7 days", d).contains("Last 7 days"))
    check("unknown question falls back to search", Assistant.offline("sandal", d).contains("Sandal"))
    check("summary has no phone numbers", !Assistant.summaryForAi(d).contains("9876543210"))

    println("\n----- sample answers -----")
    listOf("udhaar", "Ram ka udhaar", "6100 size 8", "low stock", "out of stock", "reorder", "konsa maal bika aaj",
        "best sellers", "pending orders", "supplier dues", "tasks", "compare last 7 days", "help", "sandal")
        .forEach { println("Q: $it\n" + Assistant.offline(it, d) + "\n") }
    println("----- AI summary -----\n" + Assistant.summaryForAi(d))
}
