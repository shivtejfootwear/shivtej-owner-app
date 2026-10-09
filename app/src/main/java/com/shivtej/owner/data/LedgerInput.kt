package com.shivtej.owner.data

import com.shivtej.owner.util.Ledger
import org.json.JSONObject

// Turns saved website orders and returns into the plain inputs of util/Ledger.kt (the ledger itself has no Android code).

/** Website orders as the ledger needs them. The customer mobile is read from the order's customer JSON. */
fun ledgerOrders(list: List<OrderE>): List<Ledger.WebOrder> = list.map { o ->
    Ledger.WebOrder(o.id, orderMobile(o.customerJson), o.total, o.paymentStatus, o.orderStatus, o.createdAt)
}

/** Returns and exchanges as the ledger needs them: item values are added up from the saved lines. */
fun ledgerReturns(list: List<ReturnE>): List<Ledger.LedgerReturn> = list.map { r ->
    Ledger.LedgerReturn(
        id = r.id, refType = r.refType, refId = r.refId, rejected = r.status == "REJECTED",
        goodsBack = goodsValue(r.itemsInJson), goodsOut = goodsValue(r.itemsOutJson),
        refund = r.refund, createdAt = r.createdAt
    )
}

/** Value of a list of return lines (qty x price), as saved in the return's JSON. */
fun goodsValue(json: String): Double = parseRetLines(json).sumOf { it.qty * it.price }

private fun orderMobile(customerJson: String): String = try {
    val o = JSONObject(customerJson)
    listOf("mobile", "phone").map { o.str(it) }.firstOrNull { it.isNotBlank() } ?: ""
} catch (e: Exception) {
    ""
}
