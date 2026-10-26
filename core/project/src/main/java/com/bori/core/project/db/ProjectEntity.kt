package com.bori.core.project.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.bori.core.project.model.Project
import com.bori.core.project.model.ProjectTemplate

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val packageName: String,
    val minSdk: Int,
    val targetSdk: Int,
    val rootPath: String,
    val template: String,
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun toProject() = Project(
        id = id,
        name = name,
        packageName = packageName,
        minSdk = minSdk,
        targetSdk = targetSdk,
        rootPath = rootPath,
        template = ProjectTemplate.valueOf(template),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    companion object {
        fun fromProject(project: Project) = ProjectEntity(
            id = project.id,
            name = project.name,
            packageName = project.packageName,
            minSdk = project.minSdk,
            targetSdk = project.targetSdk,
            rootPath = project.rootPath,
            template = project.template.name,
            createdAt = project.createdAt,
            updatedAt = project.updatedAt,
        )
    }
}
