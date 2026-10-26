package com.bori.app.data.build

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.bori.core.build.model.KeystoreConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KeystoreRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "keystore_config",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun get(): KeystoreConfig? {
        val path = prefs.getString(KEY_PATH, null) ?: return null
        val alias = prefs.getString(KEY_ALIAS, null) ?: return null
        val ksPass = prefs.getString(KEY_KS_PASSWORD, null) ?: return null
        val keyPass = prefs.getString(KEY_KEY_PASSWORD, null) ?: return null
        return KeystoreConfig(path, alias, ksPass, keyPass)
    }

    fun save(config: KeystoreConfig) {
        prefs.edit()
            .putString(KEY_PATH, config.keystorePath)
            .putString(KEY_ALIAS, config.keyAlias)
            .putString(KEY_KS_PASSWORD, config.keystorePassword)
            .putString(KEY_KEY_PASSWORD, config.keyPassword)
            .apply()
    }

    fun clear() {
        prefs.edit()
            .remove(KEY_PATH)
            .remove(KEY_ALIAS)
            .remove(KEY_KS_PASSWORD)
            .remove(KEY_KEY_PASSWORD)
            .apply()
    }

    fun isConfigured(): Boolean = prefs.contains(KEY_PATH)

    companion object {
        private const val KEY_PATH = "ks_path"
        private const val KEY_ALIAS = "ks_alias"
        private const val KEY_KS_PASSWORD = "ks_password"
        private const val KEY_KEY_PASSWORD = "ks_key_password"
    }
}
