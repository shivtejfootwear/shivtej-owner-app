package com.shivtej.owner.data

import android.content.Context
import androidx.room.*
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
    val total: Double,
    val method: String,
    val itemsJson: String,
    val createdAt: Long,
    val synced: Boolean
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
    val pendingSync: Boolean
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
    @Query("DELETE FROM ops WHERE status = 'DONE' AND createdAt < :before") suspend fun purgeDone(before: Long)

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
    @Query("DELETE FROM return_log WHERE id = :id") suspend fun delReturn(id: String)
    @Query("DELETE FROM stock_log WHERE opId LIKE :prefix || '%'") suspend fun delLogsStartingWith(prefix: String)
}

@Database(
    entities = [ProductE::class, VariantE::class, SizeE::class, OpE::class, BillE::class,
        StockLogE::class, OrderE::class, CustomerE::class, ReturnE::class],
    version = 2,
    exportSchema = false
)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        const val FILE = "shivtej_owner.db"
        fun build(ctx: Context): AppDb =
            Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, FILE)
                .fallbackToDestructiveMigrationFrom(1)   // version 1 never reached a shop; its data is re-downloaded from Supabase
                .build()
    }
}
