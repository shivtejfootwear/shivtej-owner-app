package com.shivtej.owner.data

// Test stubs: same field names and types as the app's entities (Room annotations removed).
data class SizeStock(val size: String, val qty: Int, val barcode: String)
data class Variant(val colour: String, val images: List<String>, val sizes: List<SizeStock>)
data class Product(
    val id: String, val article: String, val name: String, val brand: String, val category: String, val description: String,
    val mrp: Double, val price: Double, val active: Boolean, val newItem: Boolean, val images: List<String>,
    val variants: List<Variant>, val videos: List<String> = emptyList()
) { val totalStock: Int get() = variants.sumOf { v -> v.sizes.sumOf { it.qty } } }

data class BillItem(
    val productId: String, val article: String, val name: String, val colour: String,
    val size: String, val barcode: String, val qty: Int, val price: Double, val mrp: Double = 0.0
)
// test format for items: "pid|article|name|colour|size|qty|price" joined by ';'
fun parseBillItems(json: String): List<BillItem> = json.split(';').filter { it.isNotBlank() }.map {
    val p = it.split('|')
    BillItem(p[0], p[1], p[2], p[3], p[4], "", p[5].toInt(), p[6].toDouble())
}

data class BillE(
    val id: String, val billNo: String, val total: Double, val method: String, val itemsJson: String,
    val createdAt: Long, val synced: Boolean,
    val salesPersonId: String = "", val salesPerson: String = "", val customerName: String = "",
    val customerMobile: String = "", val customerAddress: String = "", val totalMrp: Double = 0.0,
    val totalDiscount: Double = 0.0, val subTotal: Double = 0.0, val taxPercent: Double = 0.0, val taxAmount: Double = 0.0,
    val amountPaid: Double = -1.0, val balance: Double = 0.0, val metaJson: String = ""
) {
    val paid: Double get() = if (amountPaid < 0.0) total else amountPaid
}

data class OrderE(
    val id: String, val customerJson: String, val itemsJson: String, val subtotal: Double, val shipping: Double,
    val total: Double, val paymentStatus: String, val orderStatus: String, val createdAt: Long,
    val trackingNo: String, val courier: String, val pendingSync: Boolean
)

data class CustomerPaymentE(
    val id: String, val mobile: String, val billId: String, val amount: Double, val method: String, val note: String,
    val paidAt: Long, val pendingSync: Boolean, val status: String = "OK", val rejectReason: String = ""
)

data class TaskE(
    val id: String, val title: String, val details: String, val dueAt: Long, val done: Boolean, val doneAt: Long,
    val createdAt: Long, val notifyId: Int
)
