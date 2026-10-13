package com.shivtej.owner.util

/** Plain numbers for the supplier ledger (no Android code, so it can be tested on a normal JVM). */
data class PurchaseAmt(val id: String, val supplierId: String, val amount: Double, val billDate: Long = 0L)

/** [billId] is only set for payments made before the on-account rule (they stay tied to that bill). */
data class PaymentAmt(val supplierId: String, val billId: String, val amount: Double, val id: String = "", val paidAt: Long = 0L)

data class SupplierTotals(val purchase: Double, val paid: Double) {
    /** What is still owed. Negative = paid in advance. */
    val outstanding: Double get() = BillMath.round2(purchase - paid)
}

/** Where the money went: per bill, what was paid and what is still owed; per payment, the bills it was spread over. */
class Allocation(
    val billPaid: Map<String, Double>,
    val balance: Map<String, Double>,
    val splits: Map<String, List<Pair<String, Double>>>
)

/** One line of the supplier history. [balanceAfter] = what is still owed after this line. */
data class LedgerLine(val time: Long, val isBill: Boolean, val refId: String, val amount: Double, val balanceAfter: Double)

/**
 * Supplier money:
 *   Total purchase = all bills of the supplier
 *   Total paid     = every payment to the supplier
 *   Outstanding    = Total purchase - Total paid
 * Payments are on account. They are spread over the OLDEST bill still owed, then the next one, in the order the money came in.
 */
object SupplierMath {
    fun totals(supplierId: String, purchases: List<PurchaseAmt>, payments: List<PaymentAmt>): SupplierTotals {
        val buy = purchases.filter { it.supplierId == supplierId }.sumOf { it.amount }
        val paid = payments.filter { it.supplierId == supplierId }.sumOf { it.amount }
        return SupplierTotals(BillMath.round2(buy), BillMath.round2(paid))
    }

    /**
     * 1) A payment tied to one bill (made before the on-account rule) goes to that bill first.
     * 2) Every other payment goes to the oldest bill still owed, then the next one, oldest payment first.
     * A bill is paid in full only when its balance reaches 0.
     */
    fun allocate(bills: List<PurchaseAmt>, pays: List<PaymentAmt>): Allocation {
        val due = LinkedHashMap<String, Double>()
        val paid = HashMap<String, Double>()
        bills.forEach { due[it.id] = it.amount; paid[it.id] = 0.0 }
        val splits = HashMap<String, MutableList<Pair<String, Double>>>()
        val pool = ArrayList<PaymentAmt>()

        for (p in pays.sortedWith(compareBy<PaymentAmt>({ it.paidAt }, { it.id }))) {
            val left = due[p.billId]
            if (p.billId.isNotBlank() && left != null) {
                val take = BillMath.round2(minOf(p.amount, maxOf(0.0, left)))
                if (take > 0.0) {
                    due[p.billId] = BillMath.round2(left - take)
                    paid[p.billId] = BillMath.round2(paid[p.billId]!! + take)
                    splits.getOrPut(p.id) { mutableListOf() }.add(p.billId to take)
                }
                val rest = BillMath.round2(p.amount - take)
                if (rest > 0.0) pool.add(p.copy(amount = rest))
            } else {
                pool.add(p)
            }
        }

        val order = bills.sortedWith(compareBy<PurchaseAmt>({ it.billDate }, { it.id })).map { it.id }
        for (p in pool.sortedWith(compareBy<PaymentAmt>({ it.paidAt }, { it.id }))) {
            var left = p.amount
            for (bid in order) {
                if (left <= 0.0) break
                val d = due[bid] ?: 0.0
                if (d <= 0.0) continue
                val take = BillMath.round2(minOf(left, d))
                due[bid] = BillMath.round2(d - take)
                paid[bid] = BillMath.round2(paid[bid]!! + take)
                splits.getOrPut(p.id) { mutableListOf() }.add(bid to take)
                left = BillMath.round2(left - take)
            }
        }

        return Allocation(
            billPaid = paid,
            balance = due.mapValues { maxOf(0.0, it.value) },
            splits = splits
        )
    }

    /** Date-wise history: every bill (owed) and every payment (paid), oldest first. A bill on the same day comes before a payment. */
    fun ledger(bills: List<PurchaseAmt>, pays: List<PaymentAmt>): List<LedgerLine> {
        val lines = ArrayList<LedgerLine>()
        bills.forEach { lines.add(LedgerLine(it.billDate, true, it.id, it.amount, 0.0)) }
        pays.forEach { lines.add(LedgerLine(it.paidAt, false, it.id, it.amount, 0.0)) }
        lines.sortWith(compareBy<LedgerLine>({ it.time }, { if (it.isBill) 0 else 1 }))
        var running = 0.0
        return lines.map { l ->
            running = BillMath.round2(running + (if (l.isBill) l.amount else -l.amount))
            l.copy(balanceAfter = running)
        }
    }
}
