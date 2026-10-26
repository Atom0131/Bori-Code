package com.bori.core.project.git

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class GitStatus(
    val staged: List<String> = emptyList(),
    val modified: List<String> = emptyList(),
    val untracked: List<String> = emptyList(),
)

data class GitCommit(
    val shortSha: String,
    val message: String,
    val author: String,
    val timestamp: Long,
)

@Singleton
class GitRepository @Inject constructor() {

    fun isRepo(dir: File): Boolean = File(dir, ".git").exists()

    suspend fun clone(url: String, dir: File, token: String = ""): Unit = withContext(Dispatchers.IO) {
        val cmd = Git.cloneRepository()
            .setURI(url)
            .setDirectory(dir)
        if (token.isNotBlank()) {
            cmd.setCredentialsProvider(UsernamePasswordCredentialsProvider("oauth2", token))
        }
        cmd.call().close()
    }

    suspend fun init(dir: File): Unit = withContext(Dispatchers.IO) {
        Git.init().setDirectory(dir).call().close()
    }

    suspend fun status(dir: File): GitStatus = withContext(Dispatchers.IO) {
        openGit(dir).use { git ->
            val s = git.status().call()
            GitStatus(
                staged = (s.added + s.changed + s.removed).sorted(),
                modified = (s.modified + s.missing).sorted(),
                untracked = s.untracked.sorted(),
            )
        }
    }

    suspend fun stageAll(dir: File): Unit = withContext(Dispatchers.IO) {
        openGit(dir).use { git ->
            git.add().addFilepattern(".").call()
            val s = git.status().call()
            if (s.missing.isNotEmpty()) {
                val rm = git.rm().setCached(true)
                s.missing.forEach { rm.addFilepattern(it) }
                rm.call()
            }
        }
    }

    suspend fun commit(
        dir: File,
        message: String,
        name: String,
        email: String,
    ): Unit = withContext(Dispatchers.IO) {
        openGit(dir).use { git ->
            git.commit()
                .setMessage(message)
                .setAuthor(name, email)
                .setCommitter(name, email)
                .call()
        }
    }

    suspend fun log(dir: File, maxCount: Int = 20): List<GitCommit> = withContext(Dispatchers.IO) {
        openGit(dir).use { git ->
            try {
                git.log().setMaxCount(maxCount).call().map { rev ->
                    GitCommit(
                        shortSha = rev.name.take(7),
                        message = rev.shortMessage,
                        author = rev.authorIdent.name,
                        timestamp = rev.authorIdent.`when`.time,
                    )
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    suspend fun getRemoteUrl(dir: File): String? = withContext(Dispatchers.IO) {
        openGit(dir).use { git ->
            git.remoteList().call()
                .firstOrNull { it.name == "origin" }
                ?.urIs?.firstOrNull()?.toString()
        }
    }

    suspend fun setRemoteUrl(dir: File, url: String): Unit = withContext(Dispatchers.IO) {
        openGit(dir).use { git ->
            val config = git.repository.config
            config.setString("remote", "origin", "url", url)
            if (config.getString("remote", "origin", "fetch") == null) {
                config.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*")
            }
            config.save()
        }
    }

    suspend fun push(dir: File, username: String, token: String): Unit = withContext(Dispatchers.IO) {
        openGit(dir).use { git ->
            git.push()
                .setRemote("origin")
                .setCredentialsProvider(UsernamePasswordCredentialsProvider(username, token))
                .call()
        }
    }

    suspend fun pull(dir: File, username: String, token: String): Unit = withContext(Dispatchers.IO) {
        openGit(dir).use { git ->
            git.pull()
                .setCredentialsProvider(UsernamePasswordCredentialsProvider(username, token))
                .call()
        }
    }

    private fun openGit(dir: File): Git {
        val repo = FileRepositoryBuilder()
            .setWorkTree(dir)
            .readEnvironment()
            .build()
        return Git(repo)
    }
}
