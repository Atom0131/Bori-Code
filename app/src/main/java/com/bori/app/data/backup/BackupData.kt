package com.bori.app.data.backup

import kotlinx.serialization.Serializable

@Serializable
data class BackupData(
    val version: Int = 1,
    val createdAt: Long,
    val apiKeys: Map<String, String?>,
    /** Keys for non-AI services, by KeyVault service id (e.g. Tavily). Absent in older backups. */
    val serviceKeys: Map<String, String?> = emptyMap(),
    val settings: SettingsBackup,
    val chatSessions: List<ChatSessionBackup>,
    val chatMessages: List<ChatMessageBackup>,
)

@Serializable
data class SettingsBackup(
    val chatProvider: String,
    val chatModel: String,
    val completionProvider: String,
    val completionModel: String,
    val kotlinTimeoutMinutes: Int,
    val compilerProfile: String,
)

@Serializable
data class ChatSessionBackup(
    val id: Long,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class ChatMessageBackup(
    val id: Long,
    val sessionId: Long,
    val role: String,
    val content: String,
    val createdAt: Long,
)
