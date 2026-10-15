package com.shivtej.owner.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase

/**
 * Checks the schema version of the existing database file BEFORE Room opens it, read-only, without changing anything.
 *
 * Supported: no file yet (fresh install), version 0 (empty), and versions 2 to 12 (migrated by the additive steps, data kept).
 * Refused: version 1 (no migration path; Room would have to rebuild it, which would delete data) and any version
 * newer than this app knows (a downgrade). Refused databases are never opened, changed or deleted by this app.
 */
object DbGuard {
    private const val CURRENT_VERSION = 13

    /** Returns null when the database may be opened safely, otherwise a message for the owner. */
    fun check(ctx: Context): String? {
        val file = ctx.getDatabasePath(AppDb.FILE)
        if (!file.exists() || file.length() == 0L) return null
        val version = try {
            val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
            try {
                db.rawQuery("PRAGMA user_version", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            } finally {
                db.close()
            }
        } catch (e: Exception) {
            // unreadable here: let Room report it; nothing is deleted in either case
            return null
        }
        return when {
            version == 0 || version in 2..CURRENT_VERSION -> null
            version == 1 -> "The data on this phone is from an old test version (database version 1). " +
                "It is kept exactly as it is and will not be changed or deleted by this app. " +
                "Please contact the developer with this message before using the app."
            version > CURRENT_VERSION -> "The data on this phone was made by a newer version of this app (database version $version). " +
                "It is kept as it is. Install the latest version of the Shivtej Owner App to continue."
            else -> "The data on this phone (database version $version) can not be opened safely by this app. It is kept as it is."
        }
    }
}
