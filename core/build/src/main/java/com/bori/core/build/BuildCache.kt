package com.bori.core.build

import java.io.File
import java.util.Properties
import java.util.zip.CRC32

/**
 * Content-hash based build cache backed by a .properties file in the build root.
 *
 * Each tag stores a CRC32 of all input file contents + their paths. Steps that
 * check [changed] before running can be skipped entirely when their inputs haven't
 * changed. Calling [invalidate] on a tag forces downstream steps to re-run even
 * if their own inputs appear clean.
 */
class BuildCache(private val buildRoot: File) {

    private val cacheFile = File(buildRoot, "build-cache.properties")
    private val props = Properties()

    init {
        if (cacheFile.exists()) {
            cacheFile.inputStream().use { props.load(it) }
        }
    }

    /**
     * Returns true if the combined CRC32 of [inputs] differs from the stored value,
     * or if [tag] has been invalidated or never recorded. Directories are expanded
     * to their contained files (sorted for stability).
     */
    fun changed(tag: String, vararg inputs: File): Boolean {
        val current = hash(*inputs)
        return props.getProperty(tag) != current
    }

    /** [List] variant — hashes [files] directly without directory expansion. */
    fun changed(tag: String, files: List<File>): Boolean {
        val current = hashFiles(files)
        return props.getProperty(tag) != current
    }

    /**
     * Stores the current CRC32 of [inputs] under [tag] and persists to disk.
     * Call after a step completes successfully.
     */
    fun markClean(tag: String, vararg inputs: File) {
        props.setProperty(tag, hash(*inputs))
        persist()
    }

    /** [List] variant — hashes [files] directly without directory expansion. */
    fun markClean(tag: String, files: List<File>) {
        props.setProperty(tag, hashFiles(files))
        persist()
    }

    /**
     * Removes [tag] from the cache so the next [changed] call returns true,
     * forcing the step (and any downstream steps) to re-run.
     */
    fun invalidate(vararg tags: String) {
        tags.forEach { props.remove(it) }
        persist()
    }

    /** Wipes all cached state — used when a full rebuild is forced. */
    fun invalidateAll() {
        props.clear()
        persist()
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun hash(vararg inputs: File): String = hashFiles(expand(*inputs))

    private fun hashFiles(files: List<File>): String {
        val crc = CRC32()
        files.sortedBy { it.absolutePath }.forEach { file ->
            crc.update(file.absolutePath.toByteArray())
            if (file.exists() && file.isFile) {
                file.forEachBlock(8192) { buffer, bytesRead ->
                    crc.update(buffer, 0, bytesRead)
                }
            }
        }
        return crc.value.toString(16)
    }

    private fun expand(vararg inputs: File): List<File> =
        inputs.flatMap { f ->
            if (f.isDirectory) {
                f.walkTopDown()
                    .filter { it.isFile }
                    .sortedBy { it.absolutePath }
                    .toList()
            } else {
                listOf(f)
            }
        }

    private fun persist() {
        cacheFile.parentFile?.mkdirs()
        cacheFile.outputStream().use { props.store(it, null) }
    }
}
