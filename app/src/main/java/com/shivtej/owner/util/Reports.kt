package com.shivtej.owner.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** One saved bill reduced to the numbers the reports need (no Android code: tested on a normal JVM). */
data class SaleRec(
    val personId: String,
    val personName: String,
    val total: Double,
    val at: Long,
    val itemQty: Int,
    val discount: Double
)

data class StaffStat(val personId: String, val name: String, val bills: Int, val sales: Double, val items: Int, val discount: Double)

data class DayTotal(val dayStart: Long, val sales: Double, val bills: Int)

object Periods {
    /** "dd-MM-yyyy" typed by the owner -> midnight of that day, or null when it is not a real date. */
    fun parseDay(text: String): Long? = try {
        SimpleDateFormat("dd-MM-yyyy", Locale.US).apply { isLenient = false }.parse(text.trim())?.time
    } catch (e: Exception) { null }

    fun startOfDay(ms: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /** Last millisecond of the day that contains [ms]. */
    fun endOfDay(ms: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = startOfDay(ms)
        c.add(Calendar.DAY_OF_MONTH, 1)
        return c.timeInMillis - 1
    }

    fun startOfMonth(ms: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        c.set(Calendar.DAY_OF_MONTH, 1)
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    fun daysAgoStart(ms: Long, days: Int): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = startOfDay(ms)
        c.add(Calendar.DAY_OF_MONTH, -days)
        return c.timeInMillis
    }
}

object Reports {
    const val NO_PERSON = ""

    /**
     * Sales per sales person between [from] and [to] (both included). Bills without a sales person (older bills, or
     * none chosen) are counted under "Not selected" so the totals always add up to the shop total.
     */
    fun staffStats(sales: List<SaleRec>, from: Long, to: Long): List<StaffStat> {
        val inRange = sales.filter { it.at in from..to }
        return inRange.groupBy { it.personId }.map { (id, list) ->
            val name = list.firstOrNull { it.personName.isNotBlank() }?.personName ?: "Not selected"
            StaffStat(id, name, list.size, BillMath.round2(list.sumOf { it.total }), list.sumOf { it.itemQty },
                BillMath.round2(list.sumOf { it.discount }))
        }.sortedByDescending { it.sales }
    }

    fun total(sales: List<SaleRec>, from: Long, to: Long): Double =
        BillMath.round2(sales.filter { it.at in from..to }.sumOf { it.total })

    fun count(sales: List<SaleRec>, from: Long, to: Long): Int = sales.count { it.at in from..to }

    fun discount(sales: List<SaleRec>, from: Long, to: Long): Double =
        BillMath.round2(sales.filter { it.at in from..to }.sumOf { it.discount })

    /** The last [days] days (oldest first, today last), days without a sale count as 0. */
    fun dailyTotals(sales: List<SaleRec>, days: Int, now: Long): List<DayTotal> {
        val out = ArrayList<DayTotal>()
        for (d in days - 1 downTo 0) {
            val s = Periods.daysAgoStart(now, d)
            val e = Periods.endOfDay(s)
            val day = sales.filter { it.at in s..e }
            out.add(DayTotal(s, BillMath.round2(day.sumOf { it.total }), day.size))
        }
        return out
    }
}
