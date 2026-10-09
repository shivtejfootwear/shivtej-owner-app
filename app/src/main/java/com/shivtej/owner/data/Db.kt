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
    val updatedAt: Long
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
    val metaJson: String = ""           // shop details + bill design exactly as they were when the bill was made
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
    val pendingSync: Boolean
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
    val pendingSync: Boolean
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
    @androidx.room.ColumnInfo(defaultValue = "''") val rejectReason: String = ""
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
    @androidx.room.ColumnInfo(defaultValue = "''") val rejectReason: String = ""
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
    @Query("SELECT * FROM products WHERE id = :id") suspend fun product(id: String): ProductE?
    @Query("SELECT * FROM products") suspend fun allProducts(): List<ProductE>
    @Query("SELECT * FROM variants WHERE productId = :id") suspend fun variantsOf(id: String): List<VariantE>
    @Query("SELECT * FROM size_stock WHERE productId = :id") suspend fun sizesOf(id: String): List<SizeE>
    @Query("SELECT * FROM size_stock WHERE barcode = :code") suspend fun sizesByBarcode(code: String): List<SizeE>
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
    @Query("SELECT * FROM bills ORDER BY createdAt DESC") fun observeBills(): Flow<List<BillE>>
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
    @Query("SELECT * FROM bills") suspend fun allBills(): List<BillE>

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
    @Query("SELECT mobile FROM pos_customers WHERE pendingSync = 1") suspend fun pendingPosCustomerIds(): List<String>
    @Query("UPDATE pos_customers SET pendingSync = 0 WHERE mobile = :mobile") suspend fun posCustomerSynced(mobile: String)

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
    @Query("SELECT * FROM purchase_bills ORDER BY billDate DESC") fun observePurchases(): Flow<List<PurchaseE>>
    @Query("SELECT id FROM purchase_bills WHERE pendingSync = 1") suspend fun pendingPurchaseIds(): List<String>
    @Query("UPDATE purchase_bills SET pendingSync = 0 WHERE id = :id") suspend fun purchaseSynced(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSupplierPayment(p: SupplierPaymentE)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSupplierPayments(p: List<SupplierPaymentE>)
    @Query("SELECT * FROM supplier_payments ORDER BY paidAt DESC") fun observeSupplierPayments(): Flow<List<SupplierPaymentE>>
    @Query("SELECT id FROM supplier_payments WHERE pendingSync = 1") suspend fun pendingPaymentIds(): List<String>
    @Query("UPDATE supplier_payments SET pendingSync = 0 WHERE id = :id") suspend fun paymentSynced(id: String)
    @Query("SELECT * FROM purchase_bills WHERE id = :id") suspend fun purchaseById(id: String): PurchaseE?
    @Query("SELECT * FROM supplier_payments WHERE billId = :billId") suspend fun paymentsOfBill(billId: String): List<SupplierPaymentE>
    @Query("UPDATE ops SET status = 'REJECTED', error = 'Its purchase bill was rejected by the server' WHERE status IN ('PENDING','FAILED') AND type = 'ADD_SUPPLIER_PAYMENT' AND refId = :ref")
    suspend fun failPaymentOp(ref: String)
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

@Database(
    entities = [ProductE::class, VariantE::class, SizeE::class, OpE::class, BillE::class,
        StockLogE::class, OrderE::class, CustomerE::class, ReturnE::class,
        StaffE::class, PosCustomerE::class, SupplierE::class, PurchaseE::class, SupplierPaymentE::class],
    version = 4,
    exportSchema = false
)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        const val FILE = "shivtej_owner.db"
        fun build(ctx: Context): AppDb =
            Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, FILE)
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4)   // additive steps only; data is kept
                // No destructive fallback: an unsupported version is refused by DbGuard before Room opens the file.
                .build()
    }
}
