package com.bori.core.project

import android.content.Context
import com.bori.core.project.db.ProjectDao
import com.bori.core.project.db.ProjectEntity
import com.bori.core.project.model.Project
import com.bori.core.project.model.ProjectTemplate
import com.bori.core.project.scaffold.ProjectScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProjectRepository @Inject constructor(
    private val dao: ProjectDao,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
) {
    companion object {
        @Volatile private var appContext: Context? = null

        /** The projects folder's name in app-specific storage. */
        const val PROJECTS_DIR = "Bori"

        fun projectsRoot(context: Context): File = File(context.getExternalFilesDir(null), PROJECTS_DIR)

        /** Called first thing in Application.onCreate, so [PROJECTS_ROOT] works before Hilt creates the repository. */
        fun attach(context: Context) {
            appContext = context.applicationContext
        }

        /** Root directory under which all projects are stored. */
        val PROJECTS_ROOT: File
            get() = projectsRoot(checkNotNull(appContext) { "ProjectRepository.attach() wasn't called" })

        /** Hidden folder inside PROJECTS_ROOT where deleted projects are moved. */
        val TRASH_ROOT: File
            get() = File(PROJECTS_ROOT, ".Trash")
    }

    init {
        appContext = context
    }

    data class TrashedProject(val dir: File, val name: String, val deletedAt: Long)

    val projects: Flow<List<Project>> =
        dao.getAllProjects().map { entities -> entities.map { it.toProject() } }

    suspend fun getProject(id: Long): Project? =
        dao.getProjectById(id)?.toProject()

    /**
     * Creates the project record in the DB and scaffolds the directory tree on disk.
     * Returns the newly created project with its generated [id].
     */
    suspend fun createProject(project: Project): Project = withContext(Dispatchers.IO) {
        val rootPath = File(PROJECTS_ROOT, project.name).absolutePath
        val toInsert = project.copy(rootPath = rootPath)
        val id = dao.insertProject(ProjectEntity.fromProject(toInsert))
        val created = toInsert.copy(id = id)
        try {
            ProjectScaffold.scaffold(created)
        } catch (e: Exception) {
            dao.deleteProject(id)
            throw e
        }
        created
    }

    suspend fun deleteProject(id: Long) = withContext(Dispatchers.IO) {
        val entity = dao.getProjectById(id) ?: return@withContext
        dao.deleteProject(id)
        val src = File(entity.rootPath)
        if (src.exists()) {
            TRASH_ROOT.mkdirs()
            val dest = File(TRASH_ROOT, "${System.currentTimeMillis()}_${src.name}")
            src.renameTo(dest)
        }
    }

    fun listTrash(): List<TrashedProject> {
        val trash = TRASH_ROOT
        if (!trash.exists()) return emptyList()
        return trash.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                val ts = dir.name.substringBefore('_').toLongOrNull() ?: return@mapNotNull null
                val name = dir.name.substringAfter('_').ifEmpty { dir.name }
                TrashedProject(dir, name, ts)
            }
            ?.sortedByDescending { it.deletedAt }
            ?: emptyList()
    }

    suspend fun restoreFromTrash(trashed: TrashedProject) = withContext(Dispatchers.IO) {
        val dest = File(PROJECTS_ROOT, trashed.name)
        trashed.dir.renameTo(dest)
        upsertFromPath(dest.absolutePath)
    }

    suspend fun permanentlyDeleteFromTrash(trashed: TrashedProject) = withContext(Dispatchers.IO) {
        trashed.dir.deleteRecursively()
    }

    private fun detectNamespace(projectDir: File): String? =
        listOf(File(projectDir, "app/build.gradle.kts"), File(projectDir, "app/build.gradle"))
            .firstOrNull { it.exists() }
            ?.readText()
            ?.let { Regex("""namespace\s*[=\s]\s*["']([^"']+)["']""").find(it)?.groupValues?.get(1) }

    /**
     * Looks up the project for [dirPath] in the DB; inserts it if missing.
     * Used to register projects created directly on-disk (e.g. by the AI agent).
     */
    suspend fun upsertFromPath(dirPath: String): Project = withContext(Dispatchers.IO) {
        dao.getByRootPath(dirPath)?.toProject() ?: run {
            val now = System.currentTimeMillis()
            val entity = ProjectEntity(
                name = File(dirPath).name,
                packageName = detectNamespace(File(dirPath)) ?: "com.example.myapp",
                minSdk = 26,
                targetSdk = 34,
                rootPath = dirPath,
                template = ProjectTemplate.EMPTY_ACTIVITY.name,
                createdAt = now,
                updatedAt = now,
            )
            val id = dao.insertProject(entity)
            entity.copy(id = id).toProject()
        }
    }

    /**
     * Scans [PROJECTS_ROOT] for subdirectories that look like Android projects
     * (contain settings.gradle or settings.gradle.kts) and registers any that
     * are not yet in the DB. Safe to call on every launch.
     */
    suspend fun syncFromFilesystem() = withContext(Dispatchers.IO) {
        val root = PROJECTS_ROOT
        if (!root.exists()) return@withContext
        root.listFiles()
            ?.filter { dir ->
                dir.isDirectory &&
                    !dir.name.startsWith(".") &&
                    (File(dir, "settings.gradle").exists() ||
                        File(dir, "settings.gradle.kts").exists())
            }
            ?.forEach { dir -> upsertFromPath(dir.absolutePath) }
    }
}
