package com.bori.app.ui.editor

/**
 * Represents a single entry in the project file tree.
 *
 * @param name Display name (file or folder name only, not the full path).
 * @param path Absolute path to this file or directory on the device.
 * @param isDirectory True if this node is a folder; false if it is a file.
 * @param children Lazily populated list of child nodes; null until the folder
 *   is expanded for the first time.
 */
data class FileNode(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val children: List<FileNode>? = null,
)

/** Files the editor can't show as text: pictures and videos (shown or listed differently) and other binaries. */
object BinaryFiles {
    private val IMAGES = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
    private val VIDEOS = setOf("mp4", "webm", "mkv", "mov", "3gp")
    private val OTHER = setOf("pdf", "zip", "apk", "aab", "jar", "so", "keystore", "jks")

    private fun ext(name: String) = name.substringAfterLast('.', "").lowercase()

    fun isImage(name: String) = ext(name) in IMAGES
    fun isVideo(name: String) = ext(name) in VIDEOS
    fun isBinary(name: String) = ext(name).let { it in IMAGES || it in VIDEOS || it in OTHER }
}
