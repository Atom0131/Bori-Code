package com.bori.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [ChatSessionEntity::class, ChatMessageEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatSessionDao(): ChatSessionDao
    abstract fun chatMessageDao(): ChatMessageDao

    companion object {
        /**
         * Ordered migrations applied by the Room builder (see AppModule).
         *
         * Currently empty (schema is at version 1). When you change an entity, bump
         * [version] above and append a Migration here, e.g.:
         *
         * ```
         * val MIGRATION_1_2 = Migration(1, 2) { db ->
         *     db.execSQL("ALTER TABLE chat_messages ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
         * }
         * ```
         * then add it to this array. Without a matching migration a version bump throws
         * IllegalStateException at runtime — this scaffold is here so that never happens
         * silently. Exported schema JSON lives in app/schemas for authoring/testing.
         */
        val MIGRATIONS: Array<Migration> = emptyArray()
    }
}
