package com.shivtej.owner.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "products")
data class ProductE(
    @PrimaryKey val id: String,
    val article: String,
    val name: String,
    val brand: String,
    val category: String,
    val description: String,
    val mrp: Double,
    val price: Double,
    val active: Boolean,
    val newItem: Boolean,
    val images: String,      // JSON array of strings (local absolute path or https url)
    val updatedAt: Long,
    // ---- added in database version 6 (product videos; empty = no videos)
    @androidx.room.ColumnInfo(defaultValue = "''") val videos: String = "",   // JSON array of strings (local path or https url)
    // ---- added in database version 14 (product type: चप्पल, सैंडल, शूज़ ... ; empty = not set)
    @androidx.room.ColumnInfo(defaultValue = "''") val productType: String = ""
)

@Entity(tableName = "variants", primaryKeys = ["productId", "colour"])
data class VariantE(val productId: String, val colour: String, val images: String)

@Entity(tableName = "size_stock", primaryKeys = ["productId", "colour", "size"], indices = [Index("barcode")])
data class SizeE(
    val productId: String,
    val colour: String,
    val size: String,
    val qty: Int,
    val barcode: String
)

/** One row per physical piece barcode (added in database version 13). A size with qty 3 can have 3 barcodes. */
@Entity(tableName = "piece_barcodes", indices = [Index(value = ["productId", "colour", "size"])])
data class PieceBarcodeE(
    @PrimaryKey val barcode: String,
    val productId: String,
    val colour: String,
    val size: String
)

@Entity(tableName = "ops")
data class OpE(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val opId: String,
    val type: String,
    val refId: String,
    val payload: String,
    val createdAt: Long,
    val status: String = "PENDING",   // PENDING / DONE / FAILED
    val attempts: Int = 0,
    val error: String? = null
)

@Entity(tableName = "bills")
data class BillE(
    @PrimaryKey val id: String,
    val billNo: String,
    val total: Double,                  // grand total (what the customer pays, tax included)
    val method: String,
    val itemsJson: String,
    val createdAt: Long,
    val synced: Boolean,
    // ---- added in database version 3 (older bills keep the defaults; nothing about them is rewritten)
    val salesPersonId: String = "",
    val salesPerson: String = "",
    val customerName: String = "",
    val customerMobile: String = "",
    val customerAddress: String = "",
    val totalMrp: Double = 0.0,
    val totalDiscount: Double = 0.0,
    val subTotal: Double = 0.0,
    val taxPercent: Double = 0.0,
    val taxAmount: Double = 0.0,
    val amountPaid: Double = -1.0,      // -1 = not recorded (older bill) -> treated as fully paid
    val balance: Double = 0.0,
    val metaJson: String = "",          // shop details + bill design exactly as they were when the bill was made
    // ---- added in database version 8: a deleted bill is kept (not erased) so its history stays traceable; lists hide it
    @androidx.room.ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
    @androidx.room.ColumnInfo(defaultValue = "0") val deletedAt: Long = 0L,
    // ---- version 15: the customer's gender on this bill ("M"/"F"/""), copied to the saved customer
    @androidx.room.ColumnInfo(defaultValue = "''") val customerGender: String = ""
) {
    /** What was actually paid (older bills have no payment record: they were paid in full). */
    val paid: Double get() = if (amountPaid < 0.0) total else amountPaid
}

/** A member of the shop staff who makes sales (soft delete only: inactive staff keep their sales history). */
@Entity(tableName = "staff")
data class StaffE(
    @PrimaryKey val id: String,
    val name: String,
    val mobile: String,
    val active: Boolean,
    val createdAt: Long,
    val pendingSync: Boolean
)

/** Shop (walk-in) customers saved by the owner app, keyed by their 10 digit mobile number. Website customers stay in "customers". */
@Entity(tableName = "pos_customers")
data class PosCustomerE(
    @PrimaryKey val mobile: String,
    val name: String,
    val address: String,
    val createdAt: Long,
    val pendingSync: Boolean,
    @androidx.room.ColumnInfo(defaultValue = "''") val photoPath: String = "",   // added in version 6: profile photo on this phone
    @androidx.room.ColumnInfo(defaultValue = "''") val photoUrl: String = "",    // the same photo on the server once synced
    // added in version 7: dues this customer had BEFORE the app was introduced (kept on this phone, not synced)
    val openingBalance: Double = 0.0,
    // version 11: the customer's ONLINE (website) contact number, kept apart from the offline (shop) number above; "" = none
    @androidx.room.ColumnInfo(defaultValue = "''") val onlineContact: String = "",
    // version 15: "M" = male, "F" = female, "" = not set (used to share male / female contacts apart)
    @androidx.room.ColumnInfo(defaultValue = "''") val gender: String = ""
)

/** A supplier / distributor the shop buys stock from. */
@Entity(tableName = "suppliers")
data class SupplierE(
    @PrimaryKey val id: String,
    val name: String,
    val mobile: String,
    val address: String,
    val notes: String,
    val active: Boolean,
    val createdAt: Long,
    val pendingSync: Boolean,
    @androidx.room.ColumnInfo(defaultValue = "''") val photoPath: String = "",   // added in version 6
    @androidx.room.ColumnInfo(defaultValue = "''") val photoUrl: String = ""
)

/** A purchase bill from a supplier. When addStock is true its items were added to the stock when it was saved. */
@Entity(tableName = "purchase_bills")
data class PurchaseE(
    @PrimaryKey val id: String,
    val supplierId: String,
    val billNo: String,
    val billDate: Long,
    val amount: Double,
    val itemsJson: String,
    val addStock: Boolean,
    val note: String,
    val pendingSync: Boolean,
    @androidx.room.ColumnInfo(defaultValue = "'OK'") val status: String = "OK",     // OK / REJECTED (refused by the server, kept for history)
    @androidx.room.ColumnInfo(defaultValue = "''") val rejectReason: String = "",
    @androidx.room.ColumnInfo(defaultValue = "''") val billFile: String = "",       // supplier's bill photo / PDF kept on this phone (absolute path)
    @androidx.room.ColumnInfo(defaultValue = "''") val billFileUrl: String = "",    // the same file on the server once synced (public URL)
    @androidx.room.ColumnInfo(defaultValue = "0") val deleted: Boolean = false      // version 12: a deleted bill is kept (not erased), counts nowhere
)

/** Money received from a shop customer. billId empty = a payment on account (it reduces the customer's total due). Added in version 6. */
@Entity(tableName = "customer_payments")
data class CustomerPaymentE(
    @PrimaryKey val id: String,
    val mobile: String,
    val billId: String,
    val amount: Double,
    val method: String,
    val note: String,
    val paidAt: Long,
    val pendingSync: Boolean,
    @androidx.room.ColumnInfo(defaultValue = "'OK'") val status: String = "OK",
    @androidx.room.ColumnInfo(defaultValue = "''") val rejectReason: String = "",
    // version 8: a payment made against a bill that was deleted is reversed (kept, but it no longer counts anywhere)
    @androidx.room.ColumnInfo(defaultValue = "0") val deleted: Boolean = false
)

/** A task or reminder kept on this phone (not synced). dueAt 0 = no due date. Added in version 6. */
@Entity(tableName = "tasks")
data class TaskE(
    @PrimaryKey val id: String,
    val title: String,
    val details: String,
    val dueAt: Long,
    val done: Boolean,
    val doneAt: Long,
    val createdAt: Long,
    val notifyId: Int                      // id used for the Android notification of this task
)

/** Money paid to a supplier. billId empty = a payment on account. */
@Entity(tableName = "supplier_payments")
data class SupplierPaymentE(
    @PrimaryKey val id: String,
    val supplierId: String,
    val billId: String,
    val amount: Double,
    val method: String,
    val note: String,
    val paidAt: Long,
    val pendingSync: Boolean,
    @androidx.room.ColumnInfo(defaultValue = "'OK'") val status: String = "OK",     // OK / REJECTED (refused by the server, kept for history)
    @androidx.room.ColumnInfo(defaultValue = "''") val rejectReason: String = "",
    @androidx.room.ColumnInfo(defaultValue = "''") val chequeNo: String = "",    // version 9: cheque number when paid by cheque
    @androidx.room.ColumnInfo(defaultValue = "0") val deleted: Boolean = false,  // version 10: a deleted payment is kept, but counts nowhere
    @androidx.room.ColumnInfo(defaultValue = "'[]'") val itemsJson: String = "[]" // version 10: goods sent back with a Return / Claim (purchase line format)
)

@Entity(tableName = "stock_log")
data class StockLogE(
    @PrimaryKey val opId: String,
    val productId: String,
    val article: String,
    val colour: String,
    val size: String,
    val delta: Int,
    val qtyAfter: Int?,
    val reason: String,
    val createdAt: Long,
    val synced: Boolean
)

@Entity(tableName = "orders")
data class OrderE(
    @PrimaryKey val id: String,
    val customerJson: String,
    val itemsJson: String,
    val subtotal: Double,
    val shipping: Double,
    val total: Double,
    val paymentStatus: String,
    val orderStatus: String,
    val createdAt: Long,
    val trackingNo: String,
    val courier: String,
    val pendingSync: Boolean
)

@Entity(tableName = "customers")
data class CustomerE(@PrimaryKey val mobile: String, val name: String, val email: String)

/** A return or an exchange against a website order (refType ORDER) or a POS bill (refType BILL). */
@Entity(tableName = "return_log")
data class ReturnE(
    @PrimaryKey val id: String,
    val kind: String,            // RETURN / EXCHANGE
    val refType: String,         // ORDER / BILL
    val refId: String,
    val reason: String,
    val refund: Double,          // money given back (negative = customer paid the difference)
    val restock: Boolean,        // false = damaged item, not put back on the shelf
    val itemsInJson: String,
    val itemsOutJson: String,
    val createdAt: Long,
    val pendingSync: Boolean,
    @androidx.room.ColumnInfo(defaultValue = "'OK'") val status: String = "OK",     // OK / REJECTED (refused by the server, kept for history)
    @androidx.room.ColumnInfo(defaultValue = "''") val rejectReason: String = ""
)

@Dao
interface AppDao {
    // ---- products
    @Query("SELECT * FROM products ORDER BY name COLLATE NOCASE") fun observeProducts(): Flow<List<ProductE>>
    @Query("SELECT * FROM variants") fun observeVariants(): Flow<List<VariantE>>
    @Query("SELECT * FROM size_stock") fun observeSizes(): Flow<List<SizeE>>
    @Query("SELECT * FROM piece_barcodes") fun observePieces(): Flow<List<PieceBarcodeE>>
    @Query("SELECT * FROM products WHERE id = :id") suspend fun product(id: String): ProductE?
    @Query("SELECT * FROM products") suspend fun allProducts(): List<ProductE>
    @Query("SELECT * FROM variants WHERE productId = :id") suspend fun variantsOf(id: String): List<VariantE>
    @Query("SELECT * FROM size_stock WHERE productId = :id") suspend fun sizesOf(id: String): List<SizeE>
    @Query("SELECT * FROM size_stock WHERE barcode = :code") suspend fun sizesByBarcode(code: String): List<SizeE>
    @Query("SELECT * FROM piece_barcodes WHERE productId = :id") suspend fun piecesOf(id: String): List<PieceBarcodeE>
    @Query("SELECT * FROM piece_barcodes WHERE barcode = :code") suspend fun pieceByBarcode(code: String): PieceBarcodeE?
    @Query("SELECT * FROM piece_barcodes WHERE productId = :pid AND colour = :colour AND size = :size")
    suspend fun piecesOfSize(pid: String, colour: String, size: String): List<PieceBarcodeE>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putPieces(p: List<PieceBarcodeE>)
    @Query("DELETE FROM piece_barcodes WHERE productId = :id") suspend fun delPieces(id: String)
    @Query("DELETE FROM piece_barcodes WHERE barcode = :code") suspend fun delPiece(code: String)
    @Query("UPDATE size_stock SET barcode = :code WHERE productId = :pid AND colour = :colour AND size = :size")
    suspend fun setSizeBarcode(pid: String, colour: String, size: String, code: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putProduct(p: ProductE)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putVariants(v: List<VariantE>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSizes(s: List<SizeE>)
    @Query("DELETE FROM variants WHERE productId = :id") suspend fun delVariants(id: String)
    @Query("DELETE FROM size_stock WHERE productId = :id") suspend fun delSizes(id: String)
    @Query("DELETE FROM products WHERE id = :id") suspend fun delProduct(id: String)
    @Query("UPDATE size_stock SET qty = MAX(0, qty + :delta) WHERE productId = :pid AND colour = :colour AND size = :size")
    suspend fun adjust(pid: String, colour: String, size: String, delta: Int)
    @Query("SELECT qty FROM size_stock WHERE productId = :pid AND colour = :colour AND size = :size")
    suspend fun qtyOf(pid: String, colour: String, size: String): Int?

    // ---- sync queue
    @Insert suspend fun addOp(o: OpE): Long
    @Query("SELECT * FROM ops WHERE status = 'PENDING' ORDER BY id") suspend fun pendingOps(): List<OpE>
    @Query("SELECT * FROM ops WHERE status IN ('PENDING','FAILED')") suspend fun openOps(): List<OpE>
    @Query("SELECT COUNT(*) FROM ops WHERE status = 'PENDING'") fun observePending(): Flow<Int>
    @Query("SELECT COUNT(*) FROM ops WHERE status = 'FAILED'") fun observeFailed(): Flow<Int>
    @Query("SELECT * FROM ops WHERE status = 'FAILED' ORDER BY id DESC LIMIT 20") fun observeFailedOps(): Flow<List<OpE>>
    @Query("UPDATE ops SET status = :status, attempts = attempts + 1, error = :err WHERE id = :id")
    suspend fun markOp(id: Long, status: String, err: String?)
    @Query("UPDATE ops SET status = 'PENDING' WHERE status = 'FAILED'") suspend fun retryFailed()
    @Query("SELECT COUNT(*) FROM ops WHERE status = 'PENDING' AND refId = :ref AND type = :type")
    suspend fun pendingCountFor(ref: String, type: String): Int
    @Query("SELECT COUNT(*) FROM supplier_payments WHERE id = :id AND status = 'REJECTED'") suspend fun paymentRejectedCount(id: String): Int
    @Query("SELECT id FROM return_log WHERE status = 'REJECTED'") suspend fun rejectedReturnIds(): List<String>
    @Query("SELECT id FROM purchase_bills WHERE status = 'REJECTED'") suspend fun rejectedPurchaseIds(): List<String>
    @Query("SELECT id FROM supplier_payments WHERE status = 'REJECTED'") suspend fun rejectedPaymentIds(): List<String>
    @Query("UPDATE return_log SET status = 'REJECTED', rejectReason = :why, pendingSync = 0 WHERE id = :id")
    suspend fun markReturnRejected(id: String, why: String)
    @Query("UPDATE purchase_bills SET status = 'REJECTED', rejectReason = :why, pendingSync = 0 WHERE id = :id")
    suspend fun markPurchaseRejected(id: String, why: String)
    // only payments that never reached the server (pendingSync = 1); a payment already on the server is not touched
    @Query("UPDATE supplier_payments SET status = 'REJECTED', rejectReason = :why, pendingSync = 0 WHERE billId = :billId AND pendingSync = 1")
    suspend fun markPaymentsRejectedOfBill(billId: String, why: String)
    @Query("UPDATE supplier_payments SET status = 'REJECTED', rejectReason = :why, pendingSync = 0 WHERE id = :id")
    suspend fun markPaymentRejected(id: String, why: String)
    @Query("UPDATE stock_log SET synced = 1 WHERE opId LIKE :prefix || '%'") suspend fun markLogsSynced(prefix: String)

    // ---- bills
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putBill(b: BillE)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putBills(b: List<BillE>)
    // deleted bills are hidden everywhere (lists, reports, ledgers); only the server merge reads them all
    @Query("SELECT * FROM bills WHERE deleted = 0 ORDER BY createdAt DESC") fun observeBills(): Flow<List<BillE>>
    @Query("UPDATE bills SET deleted = 1, deletedAt = :at WHERE id = :id") suspend fun markBillDeleted(id: String, at: Long)
    @Query("UPDATE bills SET deleted = 0, deletedAt = 0 WHERE id = :id") suspend fun markBillRestored(id: String)
    @Query("SELECT * FROM bills WHERE deleted = 1 ORDER BY deletedAt DESC") fun observeDeletedBills(): Flow<List<BillE>>
    // bills whose delete / restore has not reached the server yet (PENDING or FAILED): this phone's state wins until then
    @Query("SELECT DISTINCT refId FROM ops WHERE status IN ('PENDING','FAILED') AND type IN ('DELETE_POS_BILL','RESTORE_POS_BILL')")
    suspend fun billOpsInFlight(): List<String>
    @Query("SELECT * FROM bills WHERE id = :id") suspend fun bill(id: String): BillE?
    @Query("UPDATE bills SET synced = 1 WHERE id = :id") suspend fun billSynced(id: String)
    @Query("SELECT COUNT(*) FROM bills") suspend fun billCount(): Int

    // ---- stock log
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putLog(l: StockLogE)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putLogs(l: List<StockLogE>)
    @Query("SELECT * FROM stock_log ORDER BY createdAt DESC LIMIT 600") fun observeLog(): Flow<List<StockLogE>>

    // ---- orders / customers
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putOrder(o: OrderE)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putOrders(o: List<OrderE>)
    @Query("SELECT * FROM orders ORDER BY createdAt DESC") fun observeOrders(): Flow<List<OrderE>>
    @Query("SELECT * FROM orders WHERE id = :id") suspend fun order(id: String): OrderE?
    @Query("UPDATE orders SET pendingSync = 0 WHERE id = :id") suspend fun orderSynced(id: String)
    @Query("SELECT id FROM orders WHERE pendingSync = 1") suspend fun pendingOrderIds(): List<String>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putCustomers(c: List<CustomerE>)
    @Query("SELECT * FROM customers ORDER BY name COLLATE NOCASE") fun observeCustomers(): Flow<List<CustomerE>>

    // ---- returns / exchanges
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putReturn(r: ReturnE)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putReturns(r: List<ReturnE>)
    @Query("SELECT * FROM return_log ORDER BY createdAt DESC") fun observeReturns(): Flow<List<ReturnE>>
    @Query("UPDATE return_log SET pendingSync = 0 WHERE id = :id") suspend fun returnSynced(id: String)
    @Query("SELECT * FROM stock_log WHERE opId LIKE :prefix || '%'") suspend fun logsStartingWith(prefix: String): List<StockLogE>

    // ---- bills (version 3)
    @Query("SELECT * FROM bills WHERE deleted = 0") suspend fun allBills(): List<BillE>
    @Query("SELECT * FROM bills") suspend fun allBillsWithDeleted(): List<BillE>
    @Query("SELECT * FROM orders") suspend fun allOrders(): List<OrderE>
    @Query("SELECT * FROM return_log") suspend fun allReturns(): List<ReturnE>

    // ---- staff
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putStaff(s: StaffE)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putStaffList(s: List<StaffE>)
    @Query("SELECT * FROM staff ORDER BY name COLLATE NOCASE") fun observeStaff(): Flow<List<StaffE>>
    @Query("SELECT * FROM staff WHERE id = :id") suspend fun staffById(id: String): StaffE?
    @Query("SELECT * FROM staff") suspend fun allStaff(): List<StaffE>
    @Query("SELECT id FROM staff WHERE pendingSync = 1") suspend fun pendingStaffIds(): List<String>
    @Query("UPDATE staff SET pendingSync = 0 WHERE id = :id") suspend fun staffSynced(id: String)

    // ---- shop customers
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putPosCustomer(c: PosCustomerE)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putPosCustomers(c: List<PosCustomerE>)
    @Query("SELECT * FROM pos_customers ORDER BY name COLLATE NOCASE") fun observePosCustomers(): Flow<List<PosCustomerE>>
    @Query("SELECT * FROM pos_customers WHERE mobile = :mobile") suspend fun posCustomer(mobile: String): PosCustomerE?
    @Query("SELECT * FROM pos_customers") suspend fun allPosCustomers(): List<PosCustomerE>
    @Query("SELECT mobile FROM pos_customers WHERE pendingSync = 1") suspend fun pendingPosCustomerIds(): List<String>
    @Query("UPDATE pos_customers SET pendingSync = 0 WHERE mobile = :mobile") suspend fun posCustomerSynced(mobile: String)

    // ---- customer payments and tasks (version 6)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putCustomerPayment(p: CustomerPaymentE)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putCustomerPayments(p: List<CustomerPaymentE>)
    @Query("SELECT * FROM customer_payments WHERE deleted = 0 ORDER BY paidAt DESC") fun observeCustomerPayments(): Flow<List<CustomerPaymentE>>
    @Query("SELECT * FROM customer_payments WHERE deleted = 0") suspend fun allCustomerPayments(): List<CustomerPaymentE>
    // reverses the payments made against a deleted bill (they stop counting; the rows stay for history)
    @Query("UPDATE customer_payments SET deleted = 1 WHERE billId = :billId") suspend fun markPaymentsDeletedOfBill(billId: String)
    @Query("UPDATE customer_payments SET deleted = 0 WHERE billId = :billId") suspend fun markPaymentsRestoredOfBill(billId: String)
    @Query("SELECT id FROM customer_payments WHERE pendingSync = 1") suspend fun pendingCustomerPaymentIds(): List<String>
    @Query("SELECT id FROM customer_payments WHERE status = 'REJECTED'") suspend fun rejectedCustomerPaymentIds(): List<String>
    @Query("UPDATE customer_payments SET pendingSync = 0 WHERE id = :id") suspend fun customerPaymentSynced(id: String)
    @Query("UPDATE customer_payments SET status = 'REJECTED', rejectReason = :why, pendingSync = 0 WHERE id = :id")
    suspend fun markCustomerPaymentRejected(id: String, why: String)
    @Query("SELECT COUNT(*) FROM customer_payments WHERE id = :id AND status = 'REJECTED'") suspend fun customerPaymentRejectedCount(id: String): Int
    @Query("UPDATE ops SET status = 'REJECTED', error = 'Its payment was refused by the server' WHERE status IN ('PENDING','FAILED') AND type = 'ADD_CUSTOMER_PAYMENT' AND refId = :ref")
    suspend fun rejectOpsOfCustomerPayment(ref: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putTask(t: TaskE)
    @Query("SELECT * FROM tasks ORDER BY done ASC, CASE WHEN dueAt = 0 THEN 1 ELSE 0 END, dueAt ASC, createdAt DESC") fun observeTasks(): Flow<List<TaskE>>
    @Query("SELECT * FROM tasks WHERE id = :id") suspend fun task(id: String): TaskE?
    @Query("SELECT * FROM tasks") suspend fun allTasks(): List<TaskE>
    @Query("DELETE FROM tasks WHERE id = :id") suspend fun deleteTask(id: String)
    @Query("UPDATE pos_customers SET photoPath = :path WHERE mobile = :mobile") suspend fun setCustomerPhoto(mobile: String, path: String)
    @Query("UPDATE suppliers SET photoPath = :path WHERE id = :id") suspend fun setSupplierPhoto(id: String, path: String)
    @Query("UPDATE pos_customers SET photoUrl = :url WHERE mobile = :mobile") suspend fun setCustomerPhotoUrl(mobile: String, url: String)
    @Query("UPDATE suppliers SET photoUrl = :url WHERE id = :id") suspend fun setSupplierPhotoUrl(id: String, url: String)

    // ---- suppliers / purchases / payments
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSupplier(s: SupplierE)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSuppliers(s: List<SupplierE>)
    @Query("SELECT * FROM suppliers ORDER BY name COLLATE NOCASE") fun observeSuppliers(): Flow<List<SupplierE>>
    @Query("SELECT * FROM suppliers WHERE id = :id") suspend fun supplier(id: String): SupplierE?
    @Query("SELECT * FROM suppliers") suspend fun allSuppliers(): List<SupplierE>
    @Query("SELECT id FROM suppliers WHERE pendingSync = 1") suspend fun pendingSupplierIds(): List<String>
    @Query("UPDATE suppliers SET pendingSync = 0 WHERE id = :id") suspend fun supplierSynced(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putPurchase(p: PurchaseE)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putPurchases(p: List<PurchaseE>)
    @Query("SELECT * FROM purchase_bills WHERE deleted = 0 ORDER BY billDate DESC") fun observePurchases(): Flow<List<PurchaseE>>
    @Query("SELECT id FROM purchase_bills WHERE pendingSync = 1") suspend fun pendingPurchaseIds(): List<String>
    @Query("UPDATE purchase_bills SET pendingSync = 0 WHERE id = :id") suspend fun purchaseSynced(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSupplierPayment(p: SupplierPaymentE)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSupplierPayments(p: List<SupplierPaymentE>)
    @Query("SELECT * FROM supplier_payments WHERE deleted = 0 ORDER BY paidAt DESC") fun observeSupplierPayments(): Flow<List<SupplierPaymentE>>
    @Query("SELECT id FROM supplier_payments WHERE pendingSync = 1") suspend fun pendingPaymentIds(): List<String>
    @Query("UPDATE supplier_payments SET pendingSync = 0 WHERE id = :id") suspend fun paymentSynced(id: String)
    @Query("SELECT * FROM purchase_bills WHERE id = :id") suspend fun purchaseById(id: String): PurchaseE?
    @Query("SELECT * FROM supplier_payments WHERE billId = :billId") suspend fun paymentsOfBill(billId: String): List<SupplierPaymentE>
    @Query("SELECT * FROM purchase_bills WHERE deleted = 0") suspend fun allPurchases(): List<PurchaseE>
    @Query("SELECT * FROM supplier_payments WHERE deleted = 0") suspend fun allSupplierPayments(): List<SupplierPaymentE>
    @Query("SELECT COUNT(*) FROM ops WHERE status = 'PENDING' AND refId = :ref AND type IN ('SAVE_SUPPLIER_PAYMENT','ADD_SUPPLIER_PAYMENT')")
    suspend fun pendingPaymentOps(ref: String): Int
    @Query("UPDATE ops SET status = 'REJECTED', error = 'Its purchase bill was rejected by the server' WHERE status IN ('PENDING','FAILED') AND type = 'ADD_SUPPLIER_PAYMENT' AND refId = :ref")
    suspend fun failPaymentOp(ref: String)
    @Query("UPDATE ops SET status = 'REJECTED', error = 'Its purchase bill was rejected by the server' WHERE status IN ('PENDING','FAILED') AND type = 'SAVE_PURCHASE' AND refId = :ref")
    suspend fun failPurchaseEditOps(ref: String)
}

/**
 * Version 2 -> 3: ADD columns to bills and ADD five new tables. Nothing is dropped or rewritten, so every
 * product, size, stock figure, bill, order, return, queued change and log entry on the phone survives the update.
 * The statements mirror exactly what Room generates for the entities above (Room checks this on open).
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `bills` ADD COLUMN `salesPersonId` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `bills` ADD COLUMN `salesPerson` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `bills` ADD COLUMN `customerName` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `bills` ADD COLUMN `customerMobile` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `bills` ADD COLUMN `customerAddress` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `bills` ADD COLUMN `totalMrp` REAL NOT NULL DEFAULT 0.0")
        db.execSQL("ALTER TABLE `bills` ADD COLUMN `totalDiscount` REAL NOT NULL DEFAULT 0.0")
        db.execSQL("ALTER TABLE `bills` ADD COLUMN `subTotal` REAL NOT NULL DEFAULT 0.0")
        db.execSQL("ALTER TABLE `bills` ADD COLUMN `taxPercent` REAL NOT NULL DEFAULT 0.0")
        db.execSQL("ALTER TABLE `bills` ADD COLUMN `taxAmount` REAL NOT NULL DEFAULT 0.0")
        db.execSQL("ALTER TABLE `bills` ADD COLUMN `amountPaid` REAL NOT NULL DEFAULT -1.0")
        db.execSQL("ALTER TABLE `bills` ADD COLUMN `balance` REAL NOT NULL DEFAULT 0.0")
        db.execSQL("ALTER TABLE `bills` ADD COLUMN `metaJson` TEXT NOT NULL DEFAULT ''")

        db.execSQL("CREATE TABLE IF NOT EXISTS `staff` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `mobile` TEXT NOT NULL, " +
            "`active` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `pendingSync` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `pos_customers` (`mobile` TEXT NOT NULL, `name` TEXT NOT NULL, `address` TEXT NOT NULL, " +
            "`createdAt` INTEGER NOT NULL, `pendingSync` INTEGER NOT NULL, PRIMARY KEY(`mobile`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `suppliers` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `mobile` TEXT NOT NULL, " +
            "`address` TEXT NOT NULL, `notes` TEXT NOT NULL, `active` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
            "`pendingSync` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `purchase_bills` (`id` TEXT NOT NULL, `supplierId` TEXT NOT NULL, `billNo` TEXT NOT NULL, " +
            "`billDate` INTEGER NOT NULL, `amount` REAL NOT NULL, `itemsJson` TEXT NOT NULL, `addStock` INTEGER NOT NULL, " +
            "`note` TEXT NOT NULL, `pendingSync` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `supplier_payments` (`id` TEXT NOT NULL, `supplierId` TEXT NOT NULL, `billId` TEXT NOT NULL, " +
            "`amount` REAL NOT NULL, `method` TEXT NOT NULL, `note` TEXT NOT NULL, `paidAt` INTEGER NOT NULL, " +
            "`pendingSync` INTEGER NOT NULL, PRIMARY KEY(`id`))")
    }
}

/**
 * Version 3 -> 4: refused returns, purchase bills and supplier payments stay on the phone with status REJECTED
 * (history kept). ADD COLUMN only, with the same defaults as the entities; existing rows become status OK.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `return_log` ADD COLUMN `status` TEXT NOT NULL DEFAULT 'OK'")
        db.execSQL("ALTER TABLE `return_log` ADD COLUMN `rejectReason` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `purchase_bills` ADD COLUMN `status` TEXT NOT NULL DEFAULT 'OK'")
        db.execSQL("ALTER TABLE `purchase_bills` ADD COLUMN `rejectReason` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `supplier_payments` ADD COLUMN `status` TEXT NOT NULL DEFAULT 'OK'")
        db.execSQL("ALTER TABLE `supplier_payments` ADD COLUMN `rejectReason` TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * Version 8 -> 9: a cheque number on supplier payments. ADD COLUMN only, empty default; old payments keep an empty number.
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `supplier_payments` ADD COLUMN `chequeNo` TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * Version 10 -> 11: a separate online (website) contact number per shop customer. ADD COLUMN only, empty default.
 */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `pos_customers` ADD COLUMN `onlineContact` TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * Version 11 -> 12: a supplier purchase bill can be changed or deleted (deleted = 1 keeps the row, it counts nowhere).
 * ADD COLUMN only, default 0 (not deleted), so every existing purchase bill stays exactly as it is.
 */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `purchase_bills` ADD COLUMN `deleted` INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * Version 9 -> 10: supplier payments can be changed or deleted (deleted = 1 keeps the row but it counts nowhere),
 * and a Return / Claim keeps the goods sent back (itemsJson). ADD COLUMN only, with the same defaults as the entity.
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `supplier_payments` ADD COLUMN `deleted` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `supplier_payments` ADD COLUMN `itemsJson` TEXT NOT NULL DEFAULT '[]'")
    }
}

/**
 * Version 7 -> 8: bill deletion. A deleted bill and its reversed payments are kept with deleted = 1 (nothing is erased).
 * ADD COLUMN only, default 0 (not deleted), so every existing bill and payment stays exactly as it is.
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `bills` ADD COLUMN `deleted` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `bills` ADD COLUMN `deletedAt` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `customer_payments` ADD COLUMN `deleted` INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * Version 6 -> 7: an opening balance per shop customer (dues from before the app). ADD COLUMN only; every existing
 * customer gets 0.0, so nothing changes until the owner enters a figure.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `pos_customers` ADD COLUMN `openingBalance` REAL NOT NULL DEFAULT 0.0")
    }
}

/**
 * Version 5 -> 6: product videos, customer and supplier photos, customer payments, tasks.
 * ADD COLUMN / CREATE TABLE only. Existing rows keep every value; new columns get empty defaults.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `products` ADD COLUMN `videos` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `pos_customers` ADD COLUMN `photoPath` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `pos_customers` ADD COLUMN `photoUrl` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `suppliers` ADD COLUMN `photoPath` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `suppliers` ADD COLUMN `photoUrl` TEXT NOT NULL DEFAULT ''")
        db.execSQL("CREATE TABLE IF NOT EXISTS `customer_payments` (`id` TEXT NOT NULL, `mobile` TEXT NOT NULL, `billId` TEXT NOT NULL, " +
            "`amount` REAL NOT NULL, `method` TEXT NOT NULL, `note` TEXT NOT NULL, `paidAt` INTEGER NOT NULL, " +
            "`pendingSync` INTEGER NOT NULL, `status` TEXT NOT NULL DEFAULT 'OK', `rejectReason` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`id`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `tasks` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `details` TEXT NOT NULL, " +
            "`dueAt` INTEGER NOT NULL, `done` INTEGER NOT NULL, `doneAt` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
            "`notifyId` INTEGER NOT NULL, PRIMARY KEY(`id`))")
    }
}

/**
 * Version 4 -> 5: a supplier purchase bill can keep the supplier's bill photo or PDF (billFile, billFileUrl).
 * ADD COLUMN only, empty default; existing bills simply have no attachment.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `purchase_bills` ADD COLUMN `billFile` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `purchase_bills` ADD COLUMN `billFileUrl` TEXT NOT NULL DEFAULT ''")
    }
}

/** Version 13 -> 14: product type (चप्पल, सैंडल, शूज़ ...) on each product. Only one column is added; no data is changed. */
val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `products` ADD COLUMN `productType` TEXT NOT NULL DEFAULT ''")
    }
}

/** Version 14 -> 15: customer gender (male / female) on the customer and on the bill. Only new columns; no data changes. */
val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `pos_customers` ADD COLUMN `gender` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `bills` ADD COLUMN `customerGender` TEXT NOT NULL DEFAULT ''")
    }
}

/** Version 12 -> 13: the piece_barcodes table (one barcode per piece). Only a new table is added; no data is changed. */
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `piece_barcodes` (`barcode` TEXT NOT NULL, `productId` TEXT NOT NULL, `colour` TEXT NOT NULL, `size` TEXT NOT NULL, PRIMARY KEY(`barcode`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_piece_barcodes_productId_colour_size` ON `piece_barcodes` (`productId`, `colour`, `size`)")
    }
}

@Database(
    entities = [ProductE::class, VariantE::class, SizeE::class, OpE::class, BillE::class,
        StockLogE::class, OrderE::class, CustomerE::class, ReturnE::class,
        StaffE::class, PosCustomerE::class, SupplierE::class, PurchaseE::class, SupplierPaymentE::class,
        CustomerPaymentE::class, TaskE::class, PieceBarcodeE::class],
    version = 15,
    exportSchema = false
)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        const val FILE = "shivtej_owner.db"
        fun build(ctx: Context): AppDb =
            Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, FILE)
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15)   // additive steps only; data is kept
                // No destructive fallback: an unsupported version is refused by DbGuard before Room opens the file.
                .build()
    }
}
