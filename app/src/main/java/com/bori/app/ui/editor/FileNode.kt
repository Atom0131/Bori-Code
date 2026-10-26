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
