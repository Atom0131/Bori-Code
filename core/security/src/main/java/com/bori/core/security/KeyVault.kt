package com.bori.core.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secure storage for AI provider API keys.
 *
 * Keys are stored in [EncryptedSharedPreferences] whose master encryption key
 * lives in the Android Keystore (hardware-backed on API 28+). This means keys
 * are encrypted at rest and are never written to non-encrypted storage.
 *
 * Security properties:
 * - Keys are never logged (enforced by [RedactingLoggingInterceptor] in the AI module)
 * - The encrypted preferences file is excluded from Android backup via the
 *   manifest's `android:allowBackup="false"` and `data_extraction_rules.xml`
 * - Key strings are passed as [String] here; callers (provider clients) should
 *   avoid holding references longer than needed
 *
 * Usage:
 * ```kotlin
 * keyVault.storeKey(AiProvider.DEEPSEEK, "sk-...")
 * val key = keyVault.retrieveKey(AiProvider.DEEPSEEK)   // null if not set
 * val configured = keyVault.hasKey(AiProvider.DEEPSEEK)
 * keyVault.deleteKey(AiProvider.DEEPSEEK)
 * ```
 */
@Singleton
class KeyVault @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs: SharedPreferences by lazy { createEncryptedPrefs() }

    /**
     * Store an API [key] for the given [provider].
     * Overwrites any previously stored key for that provider.
     */
    fun storeKey(provider: AiProvider, key: String) {
        prefs.edit().putString(provider.prefKey, key).apply()
    }

    /**
     * Retrieve the stored API key for [provider], or null if not configured.
     */
    fun retrieveKey(provider: AiProvider): String? =
        prefs.getString(provider.prefKey, null)
            ?.takeIf { it.isNotBlank() }

    /**
     * Returns true if an API key has been stored for [provider].
     */
    fun hasKey(provider: AiProvider): Boolean =
        prefs.contains(provider.prefKey) && !prefs.getString(provider.prefKey, null).isNullOrBlank()

    /**
     * Delete the stored API key for [provider].
     */
    fun deleteKey(provider: AiProvider) {
        prefs.edit().remove(provider.prefKey).apply()
    }

    /**
     * Returns the list of providers that have a configured key.
     */
    fun configuredProviders(): List<AiProvider> =
        AiProvider.entries.filter { hasKey(it) }

    // -------------------------------------------------------------------------
    // Non-provider service keys (e.g. Tavily web search)
    //
    // Stored in the same encrypted file but under a "svc_" prefix so they never
    // collide with the AiProvider prefKeys.
    // -------------------------------------------------------------------------

    /** Store an API [key] for a non-AI service identified by [id] (e.g. [TAVILY]). */
    fun storeServiceKey(id: String, key: String) {
        prefs.edit().putString("svc_$id", key).apply()
    }

    /** Retrieve the stored key for service [id], or null if not configured. */
    fun retrieveServiceKey(id: String): String? =
        prefs.getString("svc_$id", null)?.takeIf { it.isNotBlank() }

    /** Returns true if a key has been stored for service [id]. */
    fun hasServiceKey(id: String): Boolean =
        !prefs.getString("svc_$id", null).isNullOrBlank()

    /** Delete the stored key for service [id]. */
    fun deleteServiceKey(id: String) {
        prefs.edit().remove("svc_$id").apply()
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private fun createEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    companion object {
        private const val PREFS_FILE_NAME = "bori_keyvault"

        /** Service id for the Tavily web-search API key. */
        const val TAVILY = "tavily"
    }
}
