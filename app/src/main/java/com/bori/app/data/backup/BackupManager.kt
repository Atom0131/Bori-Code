package com.bori.app.data.backup

import android.content.Context
import android.net.Uri
import com.bori.app.data.db.ChatMessageDao
import com.bori.app.data.db.ChatMessageEntity
import com.bori.app.data.db.ChatSessionDao
import com.bori.app.data.db.ChatSessionEntity
import com.bori.app.data.prefs.UserPreferencesRepository
import com.bori.core.project.ProjectRepository
import com.bori.core.security.AiProvider
import com.bori.core.security.KeyVault
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyVault: KeyVault,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val projectRepository: ProjectRepository,
    private val chatSessionDao: ChatSessionDao,
    private val chatMessageDao: ChatMessageDao,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val MAGIC = "BCBK".toByteArray(Charsets.US_ASCII)
    // Backups from before the rename (Mimir Codebot) have the same format under this header.
    private val OLD_MAGIC = "MMBK".toByteArray(Charsets.US_ASCII)
    private val VERSION: Byte = 1
    private val PBKDF2_ITERATIONS = 120_000
    private val SALT_LEN = 16
    private val IV_LEN = 12

    private fun deriveKey(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, PBKDF2_ITERATIONS, 256)
        return try {
            val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            SecretKeySpec(raw, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    suspend fun exportToUri(uri: Uri, password: CharArray) = withContext(Dispatchers.IO) {
        val prefs = userPreferencesRepository.preferences.first()
        val sessions = chatSessionDao.getAllSessions().first()
        val messages = chatMessageDao.getAllMessages()

        val apiKeys = AiProvider.entries.associate { provider ->
            provider.name to keyVault.retrieveKey(provider)
        }

        val backup = BackupData(
            createdAt = System.currentTimeMillis(),
            apiKeys = apiKeys,
            serviceKeys = mapOf(KeyVault.TAVILY to keyVault.retrieveServiceKey(KeyVault.TAVILY)),
            settings = SettingsBackup(
                chatProvider = prefs.chatProvider.name,
                chatModel = prefs.chatModel,
                completionProvider = prefs.completionProvider.name,
                completionModel = prefs.completionModel,
                kotlinTimeoutMinutes = prefs.kotlinTimeoutMinutes,
                compilerProfile = prefs.compilerPerformanceProfile.name,
            ),
            chatSessions = sessions.map { s ->
                ChatSessionBackup(s.id, s.title, s.createdAt, s.updatedAt)
            },
            chatMessages = messages.map { m ->
                ChatMessageBackup(m.id, m.sessionId, m.role, m.content, m.createdAt)
            },
        )

        val jsonBytes = json.encodeToString(BackupData.serializer(), backup).toByteArray()
        val projectsRoot = ProjectRepository.PROJECTS_ROOT

        // Build ZIP in memory so we can encrypt the whole thing as one blob
        val zipBytes = ByteArrayOutputStream().also { baos ->
            ZipOutputStream(baos).use { zip ->
                zip.putNextEntry(ZipEntry("backup.json"))
                zip.write(jsonBytes)
                zip.closeEntry()

                if (projectsRoot.exists()) {
                    projectsRoot.walkTopDown()
                        .filter { it.isFile }
                        .filter { file -> !file.relativeTo(projectsRoot).startsWith(".Trash") }
                        .forEach { file ->
                            val entryName = "projects/" + file.relativeTo(projectsRoot).path.replace('\\', '/')
                            zip.putNextEntry(ZipEntry(entryName))
                            file.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                }
            }
        }.toByteArray()

        val salt = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        }
        val ciphertext = cipher.doFinal(zipBytes)

        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(MAGIC)
            out.write(byteArrayOf(VERSION))
            out.write(salt)
            out.write(iv)
            out.write(ciphertext)
        }
    }

    suspend fun importFromUri(uri: Uri, password: CharArray) = withContext(Dispatchers.IO) {
        val projectsRoot = ProjectRepository.PROJECTS_ROOT
        var backupData: BackupData? = null
        val projectFiles = mutableMapOf<String, ByteArray>()

        val encrypted = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }

        val headerSize = MAGIC.size + 1 + SALT_LEN + IV_LEN
        require(encrypted.size > headerSize) { "File too small to be a Bori Code backup" }
        val magic = encrypted.copyOfRange(0, MAGIC.size)
        require(magic.contentEquals(MAGIC) || magic.contentEquals(OLD_MAGIC)) { "Not a Bori Code backup file" }

        require(encrypted[MAGIC.size] == VERSION) { "This backup is from a newer Bori Code; update the app to restore it" }
        val saltAt = MAGIC.size + 1
        val salt = encrypted.copyOfRange(saltAt, saltAt + SALT_LEN)
        val iv = encrypted.copyOfRange(saltAt + SALT_LEN, saltAt + SALT_LEN + IV_LEN)
        val ciphertext = encrypted.copyOfRange(saltAt + SALT_LEN + IV_LEN, encrypted.size)

        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        }
        val zipBytes = try {
            cipher.doFinal(ciphertext)
        } catch (e: AEADBadTagException) {
            throw IllegalArgumentException("Wrong password or corrupted backup")
        }

        ZipInputStream(zipBytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                when {
                    entry.name == "backup.json" -> {
                        backupData = json.decodeFromString(
                            BackupData.serializer(),
                            zip.readBytes().toString(Charsets.UTF_8),
                        )
                    }
                    entry.name.startsWith("projects/") && !entry.isDirectory -> {
                        val relativePath = entry.name.removePrefix("projects/")
                        projectFiles[relativePath] = zip.readBytes()
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        val data = backupData ?: error("backup.json not found in archive")

        // Restore API keys
        AiProvider.entries.forEach { provider ->
            val key = data.apiKeys[provider.name]
            if (!key.isNullOrBlank()) keyVault.storeKey(provider, key)
        }
        data.serviceKeys.forEach { (id, key) -> if (!key.isNullOrBlank()) keyVault.storeServiceKey(id, key) }

        // Restore settings
        val s = data.settings
        runCatching { AiProvider.valueOf(s.chatProvider) }.getOrNull()
            ?.let { userPreferencesRepository.updateChatProvider(it) }
        userPreferencesRepository.updateChatModel(s.chatModel)
        runCatching { AiProvider.valueOf(s.completionProvider) }.getOrNull()
            ?.let { userPreferencesRepository.updateCompletionProvider(it) }
        userPreferencesRepository.updateCompletionModel(s.completionModel)
        userPreferencesRepository.updateKotlinTimeoutMinutes(s.kotlinTimeoutMinutes)
        runCatching {
            com.bori.core.build.model.CompilerPerformanceProfile.valueOf(s.compilerProfile)
        }.getOrNull()?.let { userPreferencesRepository.updateCompilerPerformanceProfile(it) }

        // Restore chat sessions then messages (order matters for FK constraint)
        data.chatSessions.forEach { session ->
            chatSessionDao.insertSession(
                ChatSessionEntity(
                    id = session.id,
                    title = session.title,
                    createdAt = session.createdAt,
                    updatedAt = session.updatedAt,
                )
            )
        }
        data.chatMessages.forEach { msg ->
            chatMessageDao.insertMessage(
                ChatMessageEntity(
                    id = msg.id,
                    sessionId = msg.sessionId,
                    role = msg.role,
                    content = msg.content,
                    createdAt = msg.createdAt,
                )
            )
        }

        // Restore project files, skipping projects that existed before the restore. Checked up front:
        // per file, the first file written would create the folder and every other file be skipped.
        val existing = projectsRoot.list()?.toSet().orEmpty()
        val rootPrefix = projectsRoot.canonicalPath + File.separator
        projectFiles.forEach { (relativePath, bytes) ->
            val parts = relativePath.split("/")
            val topLevelDir = parts.firstOrNull() ?: return@forEach
            if (topLevelDir in existing) return@forEach
            val dest = File(projectsRoot, relativePath)
            // A crafted entry ("projects/../..") must not write outside the projects folder.
            if (!dest.canonicalPath.startsWith(rootPrefix)) return@forEach
            dest.parentFile?.mkdirs()
            dest.writeBytes(bytes)
        }

        projectRepository.syncFromFilesystem()
    }
}
