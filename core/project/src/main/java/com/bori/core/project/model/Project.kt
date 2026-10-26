package com.bori.core.project.model

data class Project(
    val id: Long = 0,
    val name: String,
    val packageName: String,
    val minSdk: Int,
    val targetSdk: Int = 35,
    val rootPath: String,
    val template: ProjectTemplate,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
