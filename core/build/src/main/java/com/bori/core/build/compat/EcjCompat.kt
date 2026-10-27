package com.bori.core.build.compat

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * The Java 11 methods ECJ calls that Android 8–12 lacks. The patchEcj Gradle task points ECJ's
 * calls here, so the same compiler runs on every Android version Bori supports.
 */
object EcjCompat {

    /** InputStream.readAllBytes() (Java 9, Android 13). */
    @JvmStatic
    fun readAllBytes(input: InputStream): ByteArray = input.readBytes()

    /** InputStream.readNBytes(len) (Java 11, Android 13): up to [len] bytes, fewer only at end of stream. */
    @JvmStatic
    fun readNBytes(input: InputStream, len: Int): ByteArray {
        require(len >= 0) { "len < 0" }
        val out = ByteArrayOutputStream(minOf(len, 8192))
        val buffer = ByteArray(minOf(len, 8192).coerceAtLeast(1))
        var remaining = len
        while (remaining > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (n < 0) break
            out.write(buffer, 0, n)
            remaining -= n
        }
        return out.toByteArray()
    }

    /** Arrays.compare(char[], char[]) (Java 9, Android 13): lexicographic, null first. */
    @JvmStatic
    fun compare(a: CharArray?, b: CharArray?): Int {
        if (a === b) return 0
        if (a == null) return -1
        if (b == null) return 1
        for (i in 0 until minOf(a.size, b.size)) {
            if (a[i] != b[i]) return a[i].compareTo(b[i])
        }
        return a.size - b.size
    }

    /** Files.readString(path) (Java 11; not in Android). */
    @JvmStatic
    fun readString(path: Path): String = String(Files.readAllBytes(path), Charsets.UTF_8)

    /**
     * Stands in for SourceVersion.valueOf("RELEASE_12") in ECJ's FileSystem, which only checks it
     * for null to learn whether the running JDK is 12+ (for reading JDK module images). Android
     * has no JDK modules, so: no.
     */
    @JvmStatic
    fun isJre12Plus(@Suppress("UNUSED_PARAMETER") name: String): Any? = null
}
