package com.bori.core.build.steps

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** A library manifest to merge, with the package its relative class names (".Foo") belong to. */
data class LibraryManifest(val file: File, val packageName: String)

data class UserLibraries(
    /** All user library classes merged, for the compile classpath and dexing. Null when there are none. */
    val classesJar: File?,
    /** One directory of AAPT2-compiled .flat files per AAR with resources. */
    val resFlatDirs: List<File>,
    val packages: List<String>,
    val manifests: List<LibraryManifest> = emptyList(),
    val assetsDirs: List<File> = emptyList(),
    /** AAR jni/ folders (jni/<abi>/lib*.so). */
    val jniDirs: List<File> = emptyList(),
    /** AAR proguard.txt consumer rules. */
    val proguardFiles: List<File> = emptyList(),
    /** True when [classesJar] already has the Kotlin stdlib (a dependency brought its own). */
    val hasKotlinStdlib: Boolean = false,
) {
    companion object {
        val EMPTY = UserLibraries(null, emptyList(), emptyList())
    }
}

/**
 * Unpacks resolved AAR/JAR artifacts: merges their classes into one JAR (for the compile
 * classpath and dexing), compiles each AAR's res/ with AAPT2, and collects manifests, assets,
 * native libraries and consumer ProGuard rules.
 *
 * Artifacts whose classes are already in the bundled AndroidX set ([bundledClassesJar]) aren't
 * added again (that would duplicate classes and resources); only their manifests are kept, so
 * their components (providers, services) still reach the app. Each AAR is unpacked and compiled
 * once into [aarCacheDir] and reused by later builds and other projects.
 */
class UserLibraryMerger(
    private val aapt2: File,
    private val aarCacheDir: File,
    private val bundledClassesJar: File? = null,
    private val bundledPackages: Set<String> = emptySet(),
) {

    fun merge(
        artifacts: List<ResolvedArtifact>,
        workDir: File,
        emit: (String) -> Unit,
    ): UserLibraries {
        if (artifacts.isEmpty()) return UserLibraries.EMPTY
        workDir.mkdirs()

        val bundledClasses = bundledClassNames()
        val fatJar = File(workDir, "user-libs.jar")
        val jarFiles = mutableListOf<File>()
        val resFlatDirs = mutableListOf<File>()
        val packages = mutableListOf<String>()
        val manifests = mutableListOf<LibraryManifest>()
        val assetsDirs = mutableListOf<File>()
        val jniDirs = mutableListOf<File>()
        val proguardFiles = mutableListOf<File>()
        val bundledNames = mutableListOf<String>()

        for (artifact in artifacts) {
            val name = artifact.coord.ifEmpty { artifact.file.nameWithoutExtension }
            if (!artifact.isAar) {
                if (isBundled(artifact.file, null, bundledClasses)) bundledNames += name else jarFiles += artifact.file
                continue
            }
            val dir = unpackAar(artifact, emit)
            val packageName = extractPackageName(File(dir, "AndroidManifest.xml"))
            packageName?.let { manifests += LibraryManifest(File(dir, "AndroidManifest.xml"), it) }
            if (isBundled(File(dir, "classes.jar"), packageName, bundledClasses)) {
                bundledNames += name
                continue
            }
            File(dir, "classes.jar").takeIf { it.exists() }?.let { jarFiles += it }
            File(dir, "libs").listFiles { f -> f.extension == "jar" }?.sortedBy { it.name }?.let { jarFiles += it }
            File(dir, "res-flat").takeIf { it.list()?.isNotEmpty() == true }?.let {
                resFlatDirs += it
                packageName?.let { p -> packages += p }
            }
            File(dir, "assets").takeIf { it.isDirectory }?.let { assetsDirs += it }
            File(dir, "jni").takeIf { it.isDirectory }?.let { jniDirs += it }
            File(dir, "proguard.txt").takeIf { it.isFile }?.let { proguardFiles += it }
        }
        if (bundledNames.isNotEmpty()) {
            emit("Already bundled, using the built-in copy: ${bundledNames.joinToString()}")
        }

        val hasStdlib = jarFiles.any { jar -> ZipFile(jar).use { it.getEntry("kotlin/Unit.class") != null } }
        val classesJar = if (jarFiles.isEmpty()) {
            fatJar.delete()
            null
        } else {
            emit("Merging ${jarFiles.size} JAR(s) into user-libs.jar")
            mergeJars(jarFiles, fatJar)
            emit("user-libs.jar: ${fatJar.length() / 1024} KB, packages: ${packages.size}")
            fatJar
        }
        return UserLibraries(
            classesJar = classesJar,
            resFlatDirs = resFlatDirs,
            packages = packages.distinct(),
            manifests = manifests,
            assetsDirs = assetsDirs,
            jniDirs = jniDirs,
            proguardFiles = proguardFiles,
            hasKotlinStdlib = hasStdlib,
        )
    }

    /**
     * The jars to put on the compile classpath for compileOnly artifacts (an AAR's classes.jar).
     * They're never dexed or packaged.
     */
    fun classpathJars(artifacts: List<ResolvedArtifact>, emit: (String) -> Unit): List<File> =
        artifacts.flatMap { artifact ->
            if (!artifact.isAar) listOf(artifact.file)
            else listOfNotNull(File(unpackAar(artifact, emit), "classes.jar").takeIf { it.exists() })
        }

    /** Unpacks [artifact] into the AAR cache and compiles its res/, once per artifact file. */
    private fun unpackAar(artifact: ResolvedArtifact, emit: (String) -> Unit): File {
        val key = artifact.coord.ifEmpty { artifact.file.nameWithoutExtension }.replace(':', '_')
        val dir = File(aarCacheDir, key)
        val done = File(dir, ".done")
        if (done.exists() && done.readText() == artifact.file.length().toString()) return dir
        dir.deleteRecursively()
        dir.mkdirs()
        emit("Extracting ${artifact.file.name}")
        ZipFile(artifact.file).use { zip ->
            for (entry in zip.entries()) {
                val dest = File(dir, entry.name)
                if (!dest.canonicalPath.startsWith(dir.canonicalPath)) continue
                if (entry.isDirectory) { dest.mkdirs(); continue }
                dest.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input -> dest.outputStream().use { input.copyTo(it) } }
            }
        }
        val resDir = File(dir, "res")
        if (resDir.isDirectory && resDir.walkTopDown().any { it.isFile }) {
            emit("AAPT2 compile res/ from ${artifact.file.nameWithoutExtension}")
            aapt2CompileDir(resDir, File(dir, "res-flat").also { it.mkdirs() }, emit)
        }
        done.writeText(artifact.file.length().toString())
        return dir
    }

    private fun bundledClassNames(): Set<String> {
        val jar = bundledClassesJar?.takeIf { it.exists() } ?: return emptySet()
        return ZipFile(jar).use { zip -> zip.entries().asSequence().map { it.name }.filter { it.endsWith(".class") }.toHashSet() }
    }

    /**
     * True when [classesJar] is a bundled library, maybe another version of it: at least half of
     * its classes are in the bundled set, or all of them are in packages the bundle has. When it
     * has no classes, its manifest package decides. Duplicates would otherwise clash at run time.
     */
    private fun isBundled(classesJar: File, packageName: String?, bundledClasses: Set<String>): Boolean {
        val classes = classesJar.takeIf { it.exists() }?.let { jar ->
            ZipFile(jar).use { zip ->
                zip.entries().asSequence().map { it.name }
                    .filter { it.endsWith(".class") && !it.startsWith("META-INF/") && it != "module-info.class" }
                    .toList()
            }
        }.orEmpty()
        if (classes.isEmpty()) return packageName != null && packageName in bundledPackages
        if (classes.count { it in bundledClasses } * 2 >= classes.size) return true
        // Another version can share few class names (collection 1.1 vs the Kotlin rewrite in
        // 1.4), but it still lives in the same packages.
        val bundledDirs = bundledDirs ?: bundledClasses.mapTo(HashSet()) { it.substringBeforeLast('/') }.also { bundledDirs = it }
        return classes.all { it.substringBeforeLast('/') in bundledDirs }
    }

    private var bundledDirs: Set<String>? = null

    private fun aapt2CompileDir(resDir: File, flatDir: File, emit: (String) -> Unit) {
        val proc = ProcessBuilder(
            aapt2.absolutePath, "compile",
            "--dir", resDir.absolutePath,
            "-o", flatDir.absolutePath,
        ).redirectErrorStream(true).start()

        BufferedReader(InputStreamReader(proc.inputStream)).use { r ->
            r.forEachLine { emit("  aapt2: $it") }
        }

        val finished = proc.waitFor(60L, TimeUnit.SECONDS)
        if (!finished) { proc.destroyForcibly(); emit("⚠ aapt2 compile timed out") }
        else if (proc.exitValue() != 0) emit("⚠ aapt2 compile failed for ${resDir.parent}")
    }

    private fun mergeJars(sources: List<File>, dest: File) {
        val seen = mutableSetOf<String>()
        val skip = Regex("^META-INF/.*\\.(SF|RSA|DSA|EC|MF)$|^module-info\\.class$|^META-INF/versions/")

        ZipOutputStream(dest.outputStream().buffered()).use { out ->
            for (source in sources) {
                if (!source.exists()) continue
                ZipFile(source).use { zip ->
                    for (entry in zip.entries()) {
                        val name = entry.name
                        if (skip.containsMatchIn(name)) continue
                        if (entry.isDirectory) {
                            if (seen.add(name)) {
                                out.putNextEntry(fixedTime(ZipEntry(name)))
                                out.closeEntry()
                            }
                            continue
                        }
                        if (!seen.add(name)) continue // first occurrence wins
                        out.putNextEntry(fixedTime(ZipEntry(name)))
                        zip.getInputStream(entry).use { it.copyTo(out) }
                        out.closeEntry()
                    }
                }
            }
        }
    }

    /** Same inputs, same bytes: later steps compare user-libs.jar's content to skip work. */
    private fun fixedTime(entry: ZipEntry) = entry.apply { time = 315532800000L } // 1980-01-01

    private fun extractPackageName(manifest: File): String? {
        if (!manifest.exists()) return null
        val text = manifest.readText()
        return Regex("""package\s*=\s*["']([^"']+)["']""").find(text)?.groupValues?.get(1)
    }
}
