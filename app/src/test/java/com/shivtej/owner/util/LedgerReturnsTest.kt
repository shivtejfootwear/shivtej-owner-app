package com.shivtej.owner.util

import com.shivtej.owner.data.BillE
import com.shivtej.owner.data.CustomerPaymentE
import org.junit.Assert.assertEquals
import org.junit.Test

/** Returns, refunds, exchanges and website orders in the customer ledger. Run with: ./gradlew :app:testDebugUnitTest */
class LedgerReturnsTest {
    private val now = 1_700_000_000_000L
    private val m = "9000000001"

    private fun bill(id: String, total: Double, paid: Double) = BillE(id, "B-$id", total, "Cash", "", now - 10_000L, true,
        customerName = "Test", customerMobile = m, amountPaid = paid)

    private fun order(id: String, total: Double, paymentStatus: String = "COD - pending", orderStatus: String = "New") =
        Ledger.WebOrder(id, m, total, paymentStatus, orderStatus, now - 5_000L)

    private fun ret(id: String, refType: String, refId: String, back: Double, out: Double = 0.0,
                    refund: Double = 0.0, rejected: Boolean = false) =
        Ledger.LedgerReturn(id, refType, refId, rejected, back, out, refund, now - 1_000L)

    private fun pay(id: String, billId: String, amount: Double) =
        CustomerPaymentE(id, m, billId, amount, "Cash", "", now - 500L, false)

    @Test fun returnOnUdhaarBillReducesWhatIsOwedWithoutCashRefund() {
        val s = Ledger.summaryOf(m, listOf(bill("u1", 1000.0, 0.0)), emptyList(), emptyList(),
            listOf(ret("r1", "BILL", "u1", 300.0)))
        assertEquals(700.0, s.outstanding, 0.001)
        assertEquals(0.0, s.refunded, 0.001)
        assertEquals(0.0, s.advance, 0.001)
    }

    @Test fun returnOfPaidBillGivesCashBackAndEndsAtZero() {
        val s = Ledger.summaryOf(m, listOf(bill("c1", 1000.0, 1000.0)), emptyList(), emptyList(),
            listOf(ret("r1", "BILL", "c1", 300.0, refund = 300.0)))
        assertEquals(0.0, s.outstanding, 0.001)
        assertEquals(0.0, s.advance, 0.001)
        assertEquals(300.0, s.refunded, 0.001)
    }

    @Test fun fullReturnOfPartlyPaidBillRefundsExactlyWhatWasPaid() {
        val s = Ledger.summaryOf(m, listOf(bill("d1", 1000.0, 400.0)), emptyList(), emptyList(),
            listOf(ret("r1", "BILL", "d1", 1000.0, refund = 400.0)))
        assertEquals(0.0, s.outstanding, 0.001)
        assertEquals(0.0, s.advance, 0.001)
    }

    @Test fun exchangeWhereCustomerPaysTheDifferenceIsAReceipt() {
        val bills = listOf(bill("e1", 1000.0, 1000.0))
        val r = listOf(ret("r1", "BILL", "e1", 300.0, out = 400.0, refund = -100.0))
        assertEquals(0.0, Ledger.summaryOf(m, bills, emptyList(), emptyList(), r).outstanding, 0.001)
        val diff = Ledger.entries(m, bills, emptyList(), emptyList(), r).single { it.kind == "DIFF_RECEIVED" }
        assertEquals(100.0, diff.credit, 0.001)
    }

    @Test fun exchangeOnUdhaarAddsNewGoodsToWhatIsOwed() {
        // 1000 owed, 300 back, 200 new goods given: 900 owed, no cash
        val s = Ledger.summaryOf(m, listOf(bill("x1", 1000.0, 0.0)), emptyList(), emptyList(),
            listOf(ret("r1", "BILL", "x1", 300.0, out = 200.0)))
        assertEquals(900.0, s.outstanding, 0.001)
    }

    @Test fun rejectedReturnIsNeverCounted() {
        val s = Ledger.summaryOf(m, listOf(bill("u1", 1000.0, 0.0)), emptyList(), emptyList(),
            listOf(ret("r1", "BILL", "u1", 300.0, rejected = true)))
        assertEquals(1000.0, s.outstanding, 0.001)
    }

    @Test fun unpaidWebsiteOrderIsOwedUntilPaymentIsRecorded() {
        val o = listOf(order("o1", 800.0))
        assertEquals(800.0, Ledger.summaryOf(m, emptyList(), emptyList(), o).outstanding, 0.001)
        val paid = listOf(pay("p1", "", 300.0))
        assertEquals(500.0, Ledger.summaryOf(m, emptyList(), paid, o).outstanding, 0.001)
    }

    @Test fun paidAndCancelledWebsiteOrdersDoNotOweMoney() {
        assertEquals(0.0, Ledger.summaryOf(m, emptyList(), emptyList(), listOf(order("o2", 800.0, "Paid"))).outstanding, 0.001)
        assertEquals(0.0, Ledger.summaryOf(m, emptyList(), emptyList(),
            listOf(order("o3", 800.0, orderStatus = "Cancelled"))).outstanding, 0.001)
    }

    @Test fun returnOnWebsiteOrderReducesTheOrderAmount() {
        val s = Ledger.summaryOf(m, emptyList(), emptyList(), listOf(order("o4", 500.0)),
            listOf(ret("r2", "ORDER", "o4", 200.0)))
        assertEquals(300.0, s.outstanding, 0.001)
    }

    @Test fun noDoubleCountingWithPaymentAndReturnOnTheSameBill() {
        val s = Ledger.summaryOf(m, listOf(bill("b9", 1000.0, 0.0)), listOf(pay("p2", "b9", 200.0)), emptyList(),
            listOf(ret("r3", "BILL", "b9", 300.0)))
        assertEquals(500.0, s.outstanding, 0.001)
        assertEquals(500.0, Ledger.billRemaining(bill("b9", 1000.0, 0.0), listOf(pay("p2", "b9", 200.0)),
            listOf(ret("r3", "BILL", "b9", 300.0))), 0.001)
    }

    @Test fun ledgerBalanceAlwaysEqualsTheCustomerNet() {
        val bills = listOf(bill("u1", 1000.0, 0.0))
        val orders = listOf(order("o1", 800.0))
        val pays = listOf(pay("p1", "u1", 200.0), pay("p2", "", 100.0))
        val rets = listOf(ret("r1", "BILL", "u1", 300.0))
        val s = Ledger.summaryOf(m, bills, pays, orders, rets)
        assertEquals(1200.0, s.outstanding, 0.001)
        assertEquals(1200.0, Ledger.entries(m, bills, pays, orders, rets).last().balance, 0.001)
    }

    @Test fun maxCashRefundIsZeroOnUdhaarAndWhateverIsExtraWhenPaid() {
        val udhaar = listOf(bill("u1", 1000.0, 0.0))
        assertEquals(0.0, Ledger.maxCashRefund("BILL", "u1", 300.0, 0.0, udhaar, emptyList(), emptyList(), emptyList()), 0.001)
        val paid = listOf(bill("c1", 1000.0, 1000.0))
        assertEquals(300.0, Ledger.maxCashRefund("BILL", "c1", 300.0, 0.0, paid, emptyList(), emptyList(), emptyList()), 0.001)
        val overpaid = listOf(bill("c2", 1000.0, 1200.0))
        // already 200 over-paid, and 500 of goods go back: 700 is owed to the customer
        assertEquals(700.0, Ledger.maxCashRefund("BILL", "c2", 500.0, 0.0, overpaid, emptyList(), emptyList(), emptyList()), 0.001)
    }

    @Test fun orderStatusReturnedAloneDoesNotChangeTheBalance() {
        val s = Ledger.summaryOf(m, emptyList(), emptyList(), listOf(order("o5", 800.0, orderStatus = "Returned")))
        assertEquals(800.0, s.outstanding, 0.001)
    }

    @Test fun returnedOrderIsCountedOnceWithItsReturnRecord() {
        val o = listOf(order("o6", 800.0, orderStatus = "Returned"))
        val r = listOf(ret("r6", "ORDER", "o6", 800.0))
        val s = Ledger.summaryOf(m, emptyList(), emptyList(), o, r)
        assertEquals(0.0, s.outstanding, 0.001)
        assertEquals(0.0, s.advance, 0.001)
        assertEquals(1, Ledger.entries(m, emptyList(), emptyList(), o, r).count { it.kind == "RETURN" })
    }

    @Test fun fullyPaidOrderIsNotOutstandingAndReturnRefundsItOnce() {
        val o = listOf(order("o7", 800.0, "Paid"))
        assertEquals(0.0, Ledger.summaryOf(m, emptyList(), emptyList(), o).outstanding, 0.001)
        val r = listOf(ret("r7", "ORDER", "o7", 800.0, refund = 800.0))
        val s = Ledger.summaryOf(m, emptyList(), emptyList(), o, r)
        assertEquals(0.0, s.outstanding, 0.001)
        assertEquals(0.0, s.advance, 0.001)
    }

    @Test fun cashRefundCannotExceedWhatTheCustomerHasPaid() {
        val o = listOf(order("o8", 800.0, "Paid"))
        assertEquals(800.0, Ledger.maxCashRefund("ORDER", "o8", 800.0, 0.0, emptyList(), o, emptyList(), emptyList()), 0.001)
        assertEquals(0.0, Ledger.maxCashRefund("ORDER", "o8", 800.0, 0.0, emptyList(), listOf(order("o9", 800.0)), emptyList(), emptyList()), 0.001)
    }

    @Test fun onAccountPaymentIsRefundableWhenTheOrderIsReturned() {
        // 800 order owed, 300 paid on account, then the whole order is returned: 300 is cash the customer is owed
        val o = listOf(order("o10", 800.0))
        val p = listOf(pay("p3", "", 300.0))
        assertEquals(300.0, Ledger.maxCashRefund("ORDER", "o10", 800.0, 0.0, emptyList(), o, p, emptyList()), 0.001)
        val r = listOf(ret("r10", "ORDER", "o10", 800.0, refund = 300.0))
        val s = Ledger.summaryOf(m, emptyList(), p, o, r)
        assertEquals(0.0, s.outstanding, 0.001)
        assertEquals(0.0, s.advance, 0.001)
    }

    @Test fun noCashRefundWhileTheCustomerOwesOnAnotherSale() {
        // Bill A owes 1000 (nothing paid). Bill B cost 500 and was paid 800 (300 extra). Overall the customer owes 700.
        val bills = listOf(bill("A", 1000.0, 0.0), bill("B", 500.0, 800.0))
        assertEquals(0.0, Ledger.maxCashRefund("BILL", "B", 500.0, 0.0, bills, emptyList(), emptyList(), emptyList()), 0.001)
        val r = listOf(ret("rB", "BILL", "B", 500.0))
        assertEquals(200.0, Ledger.summaryOf(m, bills, emptyList(), emptyList(), r).outstanding, 0.001)
    }

    @Test fun rejectedRefundIsNotCountedAndDoesNotLeaveAnExtraRow() {
        val b = listOf(bill("c5", 1000.0, 1000.0))
        val r = listOf(ret("r5", "BILL", "c5", 300.0, refund = 300.0, rejected = true))
        assertEquals(0.0, Ledger.summaryOf(m, b, emptyList(), emptyList(), r).refunded, 0.001)
        assertEquals(0, Ledger.entries(m, b, emptyList(), emptyList(), r).count { it.kind == "REFUND" })
    }
}
