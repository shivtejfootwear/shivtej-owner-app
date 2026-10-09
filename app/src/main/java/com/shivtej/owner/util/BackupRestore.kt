package com.shivtej.owner.util

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import com.shivtej.owner.data.AppDb
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Backup export and safe restore.
 *
 * Restore runs in two steps so that nothing else is using the database when it is replaced:
 *  1. [stage]: the chosen file is copied and checked (SQLite header, integrity, schema version 2 or 3, core tables) and
 *     kept as "restore-pending.db". The live database is not touched.
 *  2. [applyPending]: at the next app start, BEFORE Room opens the database, the journal is folded in, the current
 *     database is kept as "before-restore-<time>.db" (size checked), its journal files are moved aside (never deleted)
 *     and the checked copy is renamed over the database in one step. If anything fails, the old data stays in place.
 * This code never deletes user data; the only files it removes are its own temporary copies.
 */
object BackupRestore {
    private const val HEADER = "SQLite format 3\u0000"
    private const val SCHEMA_VERSION = 6          // AppDb version; backups of versions 2-6 migrate with the additive migrations
    private const val PENDING = "restore-pending.db"
    private val REQUIRED_TABLES = listOf("products", "variants", "size_stock", "bills", "ops", "stock_log", "return_log")

    /** Step 1. Copies and checks the chosen backup, keeps it for the next start. Throws IOException with a message if not usable. */
    fun stage(ctx: Context, uri: Uri) {
        val dir = dbDir(ctx)
        val tmp = File(dir, "restore-candidate.tmp")
        tmp.delete()
        val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("Can not open the backup file. Nothing was changed.")
        try {
            input.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
            validate(tmp)
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
        val pending = File(dir, PENDING)
        pending.delete()                    // an earlier staged copy that was never applied (a copy, not user data)
        if (!tmp.renameTo(pending)) {
            tmp.delete()
            throw IOException("The backup could not be prepared. Nothing was changed.")
        }
    }

    /**
     * Step 2. Called at app start before Room opens the database. Returns null when there is nothing to apply or it
     * was applied; otherwise a message (the old data is still in place).
     */
    fun applyPending(ctx: Context): String? {
        val dir = dbDir(ctx)
        val pending = File(dir, PENDING)
        if (!pending.exists()) return null
        val dbFile = ctx.getDatabasePath(AppDb.FILE)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val kept = File(dir, "before-restore-$stamp.db")
        val movedJournals = mutableListOf<Pair<File, File>>()
        return try {
            validate(pending)
            if (dbFile.exists()) {
                // fold the journal into the main file so the kept copy holds every committed change
                SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { d ->
                    d.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
                }
                dbFile.copyTo(kept, overwrite = false)
                if (kept.length() != dbFile.length()) throw IOException("the copy of the current data was incomplete")
            }
            for (suffix in listOf("-wal", "-shm")) {
                val j = File(dbFile.path + suffix)
                if (j.exists()) {
                    val target = File(kept.path + suffix)
                    if (!j.renameTo(target)) throw IOException("the old journal could not be moved aside")
                    movedJournals.add(target to j)
                }
            }
            if (!pending.renameTo(dbFile)) throw IOException("the backup could not be put in place")
            null
        } catch (e: Exception) {
            // put the old journal files back, keep the staged copy out of the way, leave the old database as it was
            for ((moved, original) in movedJournals) moved.renameTo(original)
            pending.delete()
            "The backup could not be applied, so your data is unchanged (${e.message}). Please contact the developer."
        }
    }

    /** Export: a consistent copy of the current database to the chosen file. */
    fun exportTo(ctx: Context, db: AppDb, uri: Uri) {
        val out = ctx.contentResolver.openOutputStream(uri) ?: throw IOException("Can not write the backup file")
        out.use { o ->
            if (Build.VERSION.SDK_INT >= 30) {
                // VACUUM INTO writes one self-contained snapshot, also when the app is writing at the same time
                val tmp = File(ctx.cacheDir, "backup-export.tmp")
                tmp.delete()
                try {
                    db.openHelper.writableDatabase.execSQL("VACUUM INTO '" + tmp.absolutePath.replace("'", "''") + "'")
                    tmp.inputStream().use { it.copyTo(o) }
                } finally {
                    tmp.delete()
                }
            } else {
                // older Android: checkpoint, then copy (a write made in the same instant may be missed; take the backup when idle)
                db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").close()
                ctx.getDatabasePath(AppDb.FILE).inputStream().use { it.copyTo(o) }
            }
        }
    }

    private fun dbDir(ctx: Context): File =
        ctx.getDatabasePath(AppDb.FILE).parentFile ?: throw IOException("The database folder was not found. Nothing was changed.")

    private fun validate(f: File) {
        if (f.length() < 100) throw IOException("This file is too small to be a database backup. Nothing was changed.")
        val head = ByteArray(HEADER.length)
        f.inputStream().use { it.read(head) }
        if (String(head, Charsets.ISO_8859_1) != HEADER) throw IOException("This is not a Shivtej database backup. Nothing was changed.")
        val sq = SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val check = sq.rawQuery("PRAGMA quick_check", null).use { c -> if (c.moveToFirst()) c.getString(0) else "" }
            if (check != "ok") throw IOException("The backup file is damaged ($check). Nothing was changed.")
            val version = sq.rawQuery("PRAGMA user_version", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            // version 1 is not restored (no migration path); 2 to 6 migrate without losing data
            if (version !in 2..SCHEMA_VERSION) {
                throw IOException("This backup is from another app version ($version). It can not be restored safely. Nothing was changed.")
            }
            val tables = sq.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { c ->
                val names = HashSet<String>()
                while (c.moveToNext()) names.add(c.getString(0))
                names
            }
            val missing = REQUIRED_TABLES.filter { it !in tables }
            if (missing.isNotEmpty()) throw IOException("The backup is missing tables (${missing.joinToString()}). Nothing was changed.")
        } finally {
            sq.close()
        }
    }
}
