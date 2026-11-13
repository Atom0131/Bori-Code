package com.bori.app.data.build

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import android.net.Uri
import com.bori.core.build.model.KeystoreConfig
import com.bori.core.build.steps.KeystoreCreator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
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

    private val _configured by lazy { MutableStateFlow(isConfigured()) }
    /** Whether a release key is set up; follows [save] and [clear] (the Build sheet watches it). */
    val configured: StateFlow<Boolean> get() = _configured.asStateFlow()

    /**
     * Where Bori Code keeps release keys it made or was given: app-private storage, which the
     * Linux CLIs, other apps and the projects folder can't reach. The build process (same app) reads
     * them there. Gone on uninstall, like the rest of the app's data, so the user is asked to back
     * each key up.
     */
    val keysDir: File get() = File(context.filesDir, "signing").also { it.mkdirs() }

    /**
     * Makes a new release key ([KeystoreCreator]) in [keysDir] and sets it up as the release key.
     * Returns the saved keystore file. Slow; call off the main thread.
     */
    fun create(name: String, password: String, owner: KeystoreCreator.Owner): File {
        val file = File(keysDir, KeystoreCreator.fileName(name))
        val alias = name.trim().replace(Regex("\\s+"), "-").ifEmpty { "release" }
        KeystoreCreator.create(file, alias, password, owner)
        save(KeystoreConfig(file.absolutePath, alias, password, password))
        return file
    }

    /**
     * Copies a keystore the user picked (a content:// document) into [keysDir] and returns its path.
     * A picked document's descriptor is gone once the picker returns, and builds run in their own
     * process, so the key has to be a real file the app owns.
     */
    fun import(uri: Uri, displayName: String?): File {
        val base = displayName?.substringBeforeLast('.')?.ifBlank { null } ?: "imported"
        val ext = displayName?.substringAfterLast('.', "")?.lowercase()?.takeIf { it in KEY_EXTENSIONS } ?: "jks"
        var file = File(keysDir, "$base.$ext")
        var n = 2
        while (file.exists()) file = File(keysDir, "$base-${n++}.$ext")
        val input = context.contentResolver.openInputStream(uri) ?: error("Couldn't open the file")
        input.use { i -> file.outputStream().use { o -> i.copyTo(o) } }
        return file
    }

    fun save(config: KeystoreConfig) {
        _configured.value = true
        prefs.edit()
            .putString(KEY_PATH, config.keystorePath)
            .putString(KEY_ALIAS, config.keyAlias)
            .putString(KEY_KS_PASSWORD, config.keystorePassword)
            .putString(KEY_KEY_PASSWORD, config.keyPassword)
            .apply()
    }

    fun clear() {
        _configured.value = false
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
        private val KEY_EXTENSIONS = setOf("jks", "keystore", "p12", "pfx", "bks")
    }
}
