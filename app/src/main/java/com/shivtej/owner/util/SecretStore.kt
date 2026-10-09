package com.shivtej.owner.util

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Keeps the optional AI API key encrypted on this phone (Android Keystore through EncryptedSharedPreferences).
 * The key is typed by the owner in Settings. It is never written into the source code, the database, a backup or a sync.
 * Every call is wrapped: if the secure store can not be opened, the key simply is not available (offline answers keep working).
 */
object SecretStore {
    private const val FILE = "owner_secrets"
    private const val KEY_AI = "ai_api_key"

    private fun prefs(ctx: Context) = try {
        val master = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(ctx, FILE, master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    } catch (e: Exception) { null }

    fun aiKey(ctx: Context): String = try { prefs(ctx)?.getString(KEY_AI, "") ?: "" } catch (e: Exception) { "" }

    /** Saves the key, or removes it when [key] is blank. Returns false if the secure store is not available. */
    fun setAiKey(ctx: Context, key: String): Boolean = try {
        val p = prefs(ctx) ?: return false
        if (key.isBlank()) p.edit().remove(KEY_AI).apply() else p.edit().putString(KEY_AI, key.trim()).apply()
        true
    } catch (e: Exception) { false }

    fun hasAiKey(ctx: Context): Boolean = aiKey(ctx).isNotBlank()
}
