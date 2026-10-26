package com.bori.core.project.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [ProjectEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class ProjectDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
}
