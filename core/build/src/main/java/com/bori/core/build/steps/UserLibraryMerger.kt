package com.bori.core.build.steps

import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class UserLibraries(
    val classesJar: File?,
    val resFlatDir: File?,
    val packages: List<String>,
)

/**
 * Extracts classes and resources from a set of resolved AAR/JAR artifacts, merging them
 * into a single fat JAR (for compile classpath) and a directory of AAPT2-compiled flat
 * resource files (for aapt2 link).
 */
class UserLibraryMerger(private val aapt2: File) {

    fun merge(
        artifacts: List<ResolvedArtifact>,
        workDir: File,
        emit: (String) -> Unit,
    ): UserLibraries {
        if (artifacts.isEmpty()) return UserLibraries(null, null, emptyList())
        workDir.mkdirs()

        val extractDir = File(workDir, "extracted").also { it.mkdirs() }
        val fatJar = File(workDir, "user-libs.jar")
        val flatDir = File(workDir, "user-res-flat").also { it.mkdirs() }
        val packages = mutableListOf<String>()
        val jarFiles = mutableListOf<File>()

        for ((index, artifact) in artifacts.withIndex()) {
            val artifactDir = File(extractDir, "lib-$index").also { it.mkdirs() }
            if (artifact.isAar) {
                emit("Extracting ${artifact.file.name}")
                extractAar(artifact.file, artifactDir, emit)

                val classesJar = File(artifactDir, "classes.jar")
                if (classesJar.exists()) jarFiles += classesJar

                val resDir = File(artifactDir, "res")
                if (resDir.exists() && resDir.listFiles()?.isNotEmpty() == true) {
                    emit("AAPT2 compile res/ from ${artifact.file.nameWithoutExtension}")
                    aapt2CompileDir(resDir, flatDir, emit)
                }

                extractPackageName(File(artifactDir, "AndroidManifest.xml"))
                    ?.let { packages += it }
            } else {
                jarFiles += artifact.file
            }
        }

        return if (jarFiles.isEmpty()) {
            UserLibraries(null, flatDir.takeIf { flatDir.list()?.isNotEmpty() == true }, packages)
        } else {
            emit("Merging ${jarFiles.size} JAR(s) into user-libs.jar")
            mergeJars(jarFiles, fatJar)
            emit("user-libs.jar: ${fatJar.length() / 1024} KB, packages: ${packages.size}")
            UserLibraries(
                classesJar = fatJar,
                resFlatDir = flatDir.takeIf { flatDir.list()?.isNotEmpty() == true },
                packages = packages,
            )
        }
    }

    private fun extractAar(aar: File, destDir: File, emit: (String) -> Unit) {
        ZipFile(aar).use { zip ->
            for (entry in zip.entries()) {
                val dest = File(destDir, entry.name)
                if (entry.isDirectory) { dest.mkdirs(); continue }
                dest.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                }
            }
        }
    }

    private fun aapt2CompileDir(resDir: File, flatDir: File, emit: (String) -> Unit) {
        val proc = ProcessBuilder(
            aapt2.absolutePath, "compile",
            "--dir", resDir.absolutePath,
            "-o", flatDir.absolutePath,
        ).redirectErrorStream(true).start()

        BufferedReader(InputStreamReader(proc.inputStream)).use { r ->
            r.forEachLine { emit("  aapt2: $it") }
        }

        val finished = proc.waitFor(30L, TimeUnit.SECONDS)
        if (!finished) { proc.destroyForcibly(); emit("⚠ aapt2 compile timed out") }
        else if (proc.exitValue() != 0) emit("⚠ aapt2 compile failed for ${resDir.parent}")
    }

    private fun mergeJars(sources: List<File>, dest: File) {
        val seen = mutableSetOf<String>()
        val skip = Regex("^META-INF/.*\\.(SF|RSA|DSA|MF)$")

        ZipOutputStream(dest.outputStream().buffered()).use { out ->
            for (source in sources) {
                if (!source.exists()) continue
                ZipFile(source).use { zip ->
                    for (entry in zip.entries()) {
                        val name = entry.name
                        if (skip.matches(name)) continue
                        if (entry.isDirectory) {
                            if (seen.add(name)) {
                                out.putNextEntry(ZipEntry(name))
                                out.closeEntry()
                            }
                            continue
                        }
                        if (!seen.add(name)) continue // first occurrence wins
                        out.putNextEntry(ZipEntry(name))
                        zip.getInputStream(entry).use { it.copyTo(out) }
                        out.closeEntry()
                    }
                }
            }
        }
    }

    private fun extractPackageName(manifest: File): String? {
        if (!manifest.exists()) return null
        val text = manifest.readText()
        return Regex("""package\s*=\s*["']([^"']+)["']""").find(text)?.groupValues?.get(1)
    }
}
