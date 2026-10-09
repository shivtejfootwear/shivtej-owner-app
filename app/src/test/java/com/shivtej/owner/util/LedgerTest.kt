package com.shivtej.owner.util

import com.shivtej.owner.data.BillE
import com.shivtej.owner.data.CustomerPaymentE
import org.junit.Assert.assertEquals
import org.junit.Test

/** Money rules of the customer udhaar ledger. Run with: ./gradlew :app:testDebugUnitTest */
class LedgerTest {
    private val now = 1_700_000_000_000L

    // Ram: bill of 1000 with 400 paid at the sale, bill of 300 fully paid. Sita: an old bill with no payment record.
    private val bills = listOf(
        BillE("b1", "SW-1", 1000.0, "Cash", "", now - 3_600_000L, true,
            customerName = "Ram", customerMobile = "9876543210", amountPaid = 400.0),
        BillE("b2", "SW-2", 500.0, "UPI", "", now - 86_400_000L * 8, true,
            customerName = "Sita", customerMobile = "9123456789", amountPaid = -1.0),
        BillE("b3", "SW-3", 300.0, "Cash", "", now - 60_000L, true,
            customerName = "Ram", customerMobile = "9876543210", amountPaid = 300.0)
    )

    // Ram pays 250 against bill b1. A 100 payment was refused by the server and must not count.
    private val pays = listOf(
        CustomerPaymentE("pay1", "9876543210", "b1", 250.0, "Cash", "", now - 1000, false),
        CustomerPaymentE("pay2", "9876543210", "", 100.0, "UPI", "", now - 500, false, "REJECTED", "server refused")
    )

    @Test
    fun billRemainingAfterSaleAndLaterPayment() {
        assertEquals(350.0, Ledger.billRemaining(bills[0], pays), 0.001)
    }

    @Test
    fun customerOutstandingIgnoresRejectedPayments() {
        assertEquals(350.0, Ledger.summaryOf("9876543210", bills, pays).outstanding, 0.001)
        assertEquals(0.0, Ledger.summaryOf("9876543210", bills, pays).advance, 0.001)
    }

    @Test
    fun oldBillWithoutPaymentRecordCountsAsPaid() {
        assertEquals(0.0, Ledger.summaryOf("9123456789", bills, pays).outstanding, 0.001)
    }

    @Test
    fun ledgerBalanceEndsAtOutstanding() {
        assertEquals(350.0, Ledger.entries("9876543210", bills, pays).last().balance, 0.001)
    }

    @Test
    fun rejectedPaymentIsNotInTheLedger() {
        // two bills + one counted payment
        assertEquals(3, Ledger.entries("9876543210", bills, pays).size)
    }

    @Test
    fun paymentsAboveDuesShowAsAdvance() {
        val extra = listOf(CustomerPaymentE("p9", "9876543210", "", 1000.0, "Cash", "", now, false))
        assertEquals(400.0, Ledger.summaryOf("9876543210", bills, extra).advance, 0.001)
    }
}
