package com.shivtej.owner.util

import com.shivtej.owner.data.BillE
import com.shivtej.owner.data.CustomerPaymentE

/**
 * Customer udhaar (credit) and account ledger. Pure functions with no Android code, so they can be tested on the JVM.
 *
 * Every sale of a customer is one of:
 *  - a shop (POS) bill: charge = bill total; received at sale = the amount paid on the bill (old bills: all paid);
 *  - a website order (not cancelled): charge = order total; received at sale = the whole total only when the payment
 *    status says "Paid". The website saves orders as "COD - pending" / "Pending payment", so an order stays owed until
 *    the owner records the money in the customer's ledger (as a payment on account).
 *
 * Things that change the amount after the sale:
 *  - a return / exchange (RETURN): goods handed back reduce what the customer owes (goodsBack);
 *  - an exchange: the replacement goods are a new charge (goodsOut);
 *  - a refund is CASH given back to the customer, so it raises what the customer owes again (refund).
 *    A negative refund is an exchange where the customer paid the difference, which is money received;
 *  - a payment received reduces what is owed. A payment is tied to one sale (billId = bill or order id) or is
 *    "on account" (billId empty). Both reduce the customer's TOTAL outstanding, so nothing is counted twice.
 *  - REJECTED payments, returns and refunds (refused by the server) are kept for history and are never counted.
 *
 * Per sale:     due = total - goodsBack + goodsOut - receivedAtSale - paymentsOnSale + refund
 * Per customer: net = sum of due - payments on account;  outstanding = max(0, net);  advance = max(0, -net)
 * The last running balance of the ledger always equals the customer's net.
 */
object Ledger {

    /** A website order as the ledger needs it. [mobile] is the customer's mobile taken from the order. */
    data class WebOrder(
        val id: String,
        val mobile: String,
        val total: Double,
        val paymentStatus: String,
        val orderStatus: String,
        val createdAt: Long
    )

    /** A return or exchange as the ledger needs it. The item values are added up by the caller. */
    data class LedgerReturn(
        val id: String,
        val refType: String,       // BILL or ORDER
        val refId: String,         // the bill id or the order id
        val rejected: Boolean,     // refused by the server: never counted
        val goodsBack: Double,     // value of the items the customer handed back
        val goodsOut: Double,      // value of the replacement items given (exchange only)
        val refund: Double,        // cash given back (positive); negative = the customer paid the difference
        val createdAt: Long
    )

    data class Summary(
        val mobile: String,
        val bills: Int,            // shop (POS) bills
        val billed: Double,        // sum of bill totals and website order totals
        val paidTotal: Double,     // paid at sale + payments received
        val payments: Double,      // payments received (counted ones only)
        val outstanding: Double,   // still to be paid (never below 0)
        val advance: Double,       // paid in advance, or owed back to the customer
        val orders: Int = 0,       // website orders (not cancelled)
        val returned: Double = 0.0, // value of goods taken back
        val refunded: Double = 0.0  // cash given back
    )

    /** One row of the account ledger. kind: BILL, ORDER, PAYMENT, RETURN, EXCHANGE_OUT, REFUND or DIFF_RECEIVED. */
    data class Entry(
        val at: Long,
        val kind: String,
        val refId: String,         // bill id, order id, payment id or the return's sale id
        val title: String,
        val debit: Double,         // adds to what the customer owes
        val credit: Double,        // reduces what the customer owes
        val balance: Double        // running balance after this entry
    ) {
        val isPayment: Boolean get() = kind == "PAYMENT"
    }

    /** What a POS bill or website order owed when it was made (used internally). */
    private class Sale(
        val id: String, val refType: String, val mobile: String, val total: Double,
        val receivedAtSale: Double, val at: Long, val title: String
    )

    /** What a bill still owes on its own at the time it was made. */
    fun billDue(total: Double, paid: Double): Double = BillMath.round2(maxOf(0.0, total - paid))

    fun countedPayments(list: List<CustomerPaymentE>): List<CustomerPaymentE> = list.filter { it.status != "REJECTED" }

    private fun paidOnline(status: String): Boolean = status.trim().equals("paid", ignoreCase = true)

    private fun billSale(b: BillE): Sale =
        Sale(b.id, "BILL", BillMath.normMobile(b.customerMobile), b.total, b.paid, b.createdAt,
            "Bill ${b.billNo.ifBlank { "" }}".trim())

    private fun orderSale(o: WebOrder): Sale =
        Sale(o.id, "ORDER", BillMath.normMobile(o.mobile), o.total,
            if (paidOnline(o.paymentStatus)) o.total else 0.0, o.createdAt, "Online order")

    private fun salesOf(bills: List<BillE>, orders: List<WebOrder>): List<Sale> =
        bills.map { billSale(it) } + orders.filter { it.orderStatus != "Cancelled" }.map { orderSale(it) }

    private fun activeReturns(returns: List<LedgerReturn>): List<LedgerReturn> = returns.filter { !it.rejected }

    private fun paymentsOn(id: String, payments: List<CustomerPaymentE>): Double =
        countedPayments(payments).filter { it.billId == id }.sumOf { it.amount }

    /** What one sale still owes (positive) or is owed back (negative), counting its payments, returns and refunds. */
    private fun dueOf(s: Sale, payments: List<CustomerPaymentE>, returns: List<LedgerReturn>): Double {
        val mine = activeReturns(returns).filter { it.refType == s.refType && it.refId == s.id }
        return s.total - mine.sumOf { it.goodsBack } + mine.sumOf { it.goodsOut } - s.receivedAtSale -
            paymentsOn(s.id, payments) + mine.sumOf { it.refund }
    }

    /** Remaining amount of one POS bill after the payments and returns on it (never below 0). */
    fun billRemaining(bill: BillE, payments: List<CustomerPaymentE>, returns: List<LedgerReturn> = emptyList()): Double =
        BillMath.round2(maxOf(0.0, dueOf(billSale(bill), payments, returns)))

    /**
     * The most cash that can be given back for a return or exchange on one sale. Cash goes back only when the customer
     * (over all their sales and payments) has paid more than what is still owed after the return.
     * The rest of the value reduces the udhaar instead.
     */
    fun maxCashRefund(
        refType: String, refId: String, backValue: Double, outValue: Double,
        bills: List<BillE>, orders: List<WebOrder>, payments: List<CustomerPaymentE>, returns: List<LedgerReturn>
    ): Double {
        val sale = salesOf(bills, orders).firstOrNull { it.refType == refType && it.id == refId } ?: return 0.0
        // The customer's whole account decides: payments on account are also money the customer has with the shop.
        val before = summaryOf(sale.mobile, bills, payments, orders, returns)
        val netBefore = before.outstanding - before.advance      // > 0 owes the shop, < 0 the shop owes the customer
        return BillMath.round2(maxOf(0.0, backValue - outValue - netBefore))
    }

    fun summaryOf(
        mobile: String, bills: List<BillE>, payments: List<CustomerPaymentE>,
        orders: List<WebOrder> = emptyList(), returns: List<LedgerReturn> = emptyList()
    ): Summary {
        val m = BillMath.normMobile(mobile)
        val mySales = salesOf(bills, orders).filter { it.mobile == m }
        val mySaleIds = mySales.map { it.id }.toSet()
        val myPays = countedPayments(payments).filter { BillMath.normMobile(it.mobile) == m }
        val onAccount = myPays.filter { it.billId !in mySaleIds }.sumOf { it.amount }
        val net = BillMath.round2(mySales.sumOf { dueOf(it, payments, returns) } - onAccount)
        val payTotal = myPays.sumOf { it.amount }
        val myReturns = activeReturns(returns).filter { r ->
            mySales.any { it.refType == r.refType && it.id == r.refId }
        }
        return Summary(
            mobile = m,
            bills = mySales.count { it.refType == "BILL" },
            billed = BillMath.round2(mySales.sumOf { it.total }),
            paidTotal = BillMath.round2(mySales.sumOf { it.receivedAtSale } + payTotal),
            payments = BillMath.round2(payTotal),
            outstanding = maxOf(0.0, net),
            advance = maxOf(0.0, -net),
            orders = mySales.count { it.refType == "ORDER" },
            returned = BillMath.round2(myReturns.sumOf { it.goodsBack }),
            refunded = BillMath.round2(myReturns.sumOf { maxOf(0.0, it.refund) })
        )
    }

    /** One summary per customer that has bills, orders or payments. */
    fun summaries(
        bills: List<BillE>, payments: List<CustomerPaymentE>,
        orders: List<WebOrder> = emptyList(), returns: List<LedgerReturn> = emptyList()
    ): Map<String, Summary> {
        val mobiles = (bills.map { BillMath.normMobile(it.customerMobile) } +
            payments.map { BillMath.normMobile(it.mobile) } +
            orders.map { BillMath.normMobile(it.mobile) })
            .filter { it.length == 10 }.toSet()
        return mobiles.associateWith { summaryOf(it, bills, payments, orders, returns) }
    }

    private fun kindPriority(kind: String): Int = when (kind) {
        "BILL", "ORDER" -> 0      // a sale comes before the things that happen after it
        "PAYMENT" -> 2            // a payment on the same moment comes after the sale
        else -> 1
    }

    /** The account ledger in date order, with the running balance. The last balance equals the customer's net. */
    fun entries(
        mobile: String, bills: List<BillE>, payments: List<CustomerPaymentE>,
        orders: List<WebOrder> = emptyList(), returns: List<LedgerReturn> = emptyList()
    ): List<Entry> {
        val m = BillMath.normMobile(mobile)
        val mySales = salesOf(bills, orders).filter { it.mobile == m }
        val rows = ArrayList<Entry>()
        for (s in mySales) {
            rows.add(Entry(s.at, if (s.refType == "BILL") "BILL" else "ORDER", s.id, s.title,
                BillMath.round2(s.total - s.receivedAtSale), 0.0, 0.0))
        }
        for (p in countedPayments(payments).filter { BillMath.normMobile(it.mobile) == m }) {
            rows.add(Entry(p.paidAt, "PAYMENT", p.id,
                if (p.billId.isBlank()) "Payment (on account)" else "Payment against a sale", 0.0, p.amount, 0.0))
        }
        val mine = activeReturns(returns).filter { r -> mySales.any { it.refType == r.refType && it.id == r.refId } }
        for (r in mine) {
            if (r.goodsBack > 0.0) rows.add(Entry(r.createdAt, "RETURN", r.refId, "Items taken back",
                0.0, r.goodsBack, 0.0))
            if (r.goodsOut > 0.0) rows.add(Entry(r.createdAt, "EXCHANGE_OUT", r.refId, "Exchange items given",
                r.goodsOut, 0.0, 0.0))
            if (r.refund > 0.0) rows.add(Entry(r.createdAt, "REFUND", r.refId, "Cash refund given",
                r.refund, 0.0, 0.0))
            if (r.refund < 0.0) rows.add(Entry(r.createdAt, "DIFF_RECEIVED", r.refId, "Exchange difference received",
                0.0, -r.refund, 0.0))
        }
        rows.sortWith(compareBy<Entry> { it.at }.thenBy { kindPriority(it.kind) })
        var running = 0.0
        return rows.map { e ->
            running = BillMath.round2(running + e.debit - e.credit)
            e.copy(balance = running)
        }
    }
}
