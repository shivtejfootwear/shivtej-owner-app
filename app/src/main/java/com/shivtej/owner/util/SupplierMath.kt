package com.shivtej.owner.util

/** Plain numbers for the supplier ledger (no Android code, tested on a normal JVM). */
data class PurchaseAmt(val id: String, val supplierId: String, val amount: Double)
data class PaymentAmt(val supplierId: String, val billId: String, val amount: Double)

data class SupplierTotals(val purchase: Double, val paid: Double) {
    /** What is still owed. Negative = paid in advance. */
    val outstanding: Double get() = BillMath.round2(purchase - paid)
}

/**
 * Supplier money:
 *   Total purchase = all bills of the supplier
 *   Total paid     = every payment to the supplier (against a bill or on account)
 *   Outstanding    = Total purchase - Total paid
 * A payment against a bill lowers that bill's balance; a payment on account only lowers the supplier's outstanding.
 */
object SupplierMath {
    fun totals(supplierId: String, purchases: List<PurchaseAmt>, payments: List<PaymentAmt>): SupplierTotals {
        val buy = purchases.filter { it.supplierId == supplierId }.sumOf { it.amount }
        val paid = payments.filter { it.supplierId == supplierId }.sumOf { it.amount }
        return SupplierTotals(BillMath.round2(buy), BillMath.round2(paid))
    }

    fun billPaid(billId: String, payments: List<PaymentAmt>): Double =
        BillMath.round2(payments.filter { it.billId == billId }.sumOf { it.amount })

    fun billBalance(bill: PurchaseAmt, payments: List<PaymentAmt>): Double {
        val b = BillMath.round2(bill.amount - billPaid(bill.id, payments))
        return if (b < 0.0) 0.0 else b
    }
}
