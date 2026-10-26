package com.bori.core.project.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun getAllProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getProjectById(id: Long): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProject(entity: ProjectEntity): Long

    @Update
    suspend fun updateProject(entity: ProjectEntity)

    @Query("SELECT * FROM projects WHERE rootPath = :rootPath LIMIT 1")
    suspend fun getByRootPath(rootPath: String): ProjectEntity?

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteProject(id: Long)
}
