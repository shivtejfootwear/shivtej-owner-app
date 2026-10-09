package com.shivtej.owner.util

import com.shivtej.owner.data.BillE
import com.shivtej.owner.data.CustomerPaymentE
import org.junit.Assert.assertEquals
import org.junit.Test

/** Opening balances (dues from before the app) and the oldest unpaid date. Run with: ./gradlew :app:testDebugUnitTest */
class LedgerOpeningTest {
    private val now = 1_700_000_000_000L
    private val m = "9000000002"

    private fun bill(id: String, total: Double, paid: Double, at: Long) = BillE(id, "B-$id", total, "Cash", "", at, true,
        customerName = "Test", customerMobile = m, amountPaid = paid)

    private fun pay(id: String, billId: String, amount: Double) = CustomerPaymentE(id, m, billId, amount, "Cash", "", now, false)

    @Test fun openingBalanceIsOwedUntilItIsPaid() {
        val o = mapOf(m to 500.0)
        assertEquals(500.0, Ledger.summaryOf(m, emptyList(), emptyList(), emptyList(), emptyList(), o).outstanding, 0.001)
        assertEquals(200.0, Ledger.summaryOf(m, emptyList(), listOf(pay("p1", "", 300.0)), emptyList(), emptyList(), o).outstanding, 0.001)
    }

    @Test fun openingBalanceAddsToTheSalesOfTheCustomer() {
        val bills = listOf(bill("b1", 1000.0, 0.0, now - 5_000L))
        val o = mapOf(m to 500.0)
        assertEquals(1500.0, Ledger.summaryOf(m, bills, emptyList(), emptyList(), emptyList(), o).outstanding, 0.001)
        assertEquals(1500.0, Ledger.entries(m, bills, emptyList(), emptyList(), emptyList(), o).last().balance, 0.001)
    }

    @Test fun openingBalanceIsShownAsTheFirstLedgerRow() {
        val rows = Ledger.entries(m, emptyList(), emptyList(), emptyList(), emptyList(), mapOf(m to 500.0))
        assertEquals("OPENING", rows.first().kind)
        assertEquals(500.0, rows.first().debit, 0.001)
    }

    @Test fun negativeOpeningIsMoneyTheShopOwesTheCustomer() {
        val s = Ledger.summaryOf(m, emptyList(), emptyList(), emptyList(), emptyList(), mapOf(m to -200.0))
        assertEquals(0.0, s.outstanding, 0.001)
        assertEquals(200.0, s.advance, 0.001)
    }

    @Test fun openingBalanceBelongsOnlyToItsOwnCustomer() {
        assertEquals(0.0, Ledger.summaryOf("9000000003", emptyList(), emptyList(), emptyList(), emptyList(), mapOf(m to 500.0)).outstanding, 0.001)
    }

    @Test fun oldestDueIsTheOpeningBalanceThenTheOldestUnpaidSale() {
        val older = bill("old", 300.0, 0.0, now - 20_000L)
        val newer = bill("new", 400.0, 0.0, now - 10_000L)
        val bills = listOf(older, newer)
        assertEquals(Ledger.OPENING_AT,
            Ledger.summaryOf(m, bills, emptyList(), emptyList(), emptyList(), mapOf(m to 100.0)).oldestDueAt)
        assertEquals(now - 20_000L,
            Ledger.summaryOf(m, bills, emptyList(), emptyList(), emptyList(), emptyMap()).oldestDueAt)
        // the older bill is paid in full: the newer one is now the oldest unpaid amount
        assertEquals(now - 10_000L,
            Ledger.summaryOf(m, bills, listOf(pay("p2", "old", 300.0)), emptyList(), emptyList(), emptyMap()).oldestDueAt)
    }

    @Test fun nothingDueHasNoOldestDate() {
        assertEquals(0L, Ledger.summaryOf(m, emptyList(), emptyList(), emptyList(), emptyList(), emptyMap()).oldestDueAt)
    }

    @Test fun cashRefundIncludesTheOpeningBalanceOfTheCustomer() {
        // the customer owes 500 from before the app, so a 300 return on a fully paid bill gives no cash back
        val paid = listOf(bill("c1", 300.0, 300.0, now - 5_000L))
        assertEquals(0.0, Ledger.maxCashRefund("BILL", "c1", 300.0, 0.0, paid, emptyList(), emptyList(), emptyList(), mapOf(m to 500.0)), 0.001)
    }
}
