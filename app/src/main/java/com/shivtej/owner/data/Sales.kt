package com.shivtej.owner.data

import com.shivtej.owner.util.SaleRec

/** One saved POS bill as a sale line for the staff / shop reports. Older bills have no sales person ("Not selected"). */
fun BillE.toSale(): SaleRec = SaleRec(
    personId = salesPersonId,
    personName = salesPerson,
    total = total,
    at = createdAt,
    itemQty = parseBillItems(itemsJson).sumOf { it.qty },
    discount = totalDiscount
)

fun List<BillE>.toSales(): List<SaleRec> = map { it.toSale() }
