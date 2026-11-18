package com.bori.core.keyboard.theme

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.bori.core.keyboard.ime.DiagLog
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Stage 3.8: themes as files. A .boritheme is a zip with theme.json and, for a photo
 * background, the picture next to it. Sharing bakes in the user's Keys & letters and
 * Background picks, so the file looks like the keyboard they see. Never contains typed text.
 *
 * Import is careful with files from anywhere: size limits, plain file names only (no folders,
 * no ".."), pictures must decode, and the theme always gets a new id in Mine.
 */
object ThemeShare {
    const val EXTENSION = ".boritheme"
    const val MIME = "application/zip"
    private const val JSON = "theme.json"
    private const val MAX_JSON = 256 * 1024
    private const val MAX_IMAGE = 12 * 1024 * 1024
    private const val MAX_ENTRIES = 8
    private val IMAGE_EXT = listOf(".jpg", ".jpeg", ".png", ".webp")

    /** Writes [spec] (with [o] baked in) to the cache and returns the file to share. */
    fun export(context: Context, spec: ThemeSpec, o: ThemeOverrides): File {
        val store = ThemeStore(context)
        val images = LinkedHashMap<String, File>()
        // Photos the theme itself carries sit in its folder; the user's own pick in backgrounds/.
        fun carry(b: BackgroundSpec?, dir: File): BackgroundSpec? {
            if (b == null || b.type != "photo") return b
            val f = b.image?.let { File(dir, File(it).name) }?.takeIf { it.isFile } ?: return null
            images[f.name] = f
            return b.copy(image = f.name)
        }
        val themeDir = store.dirOf(spec.id)
        val userBg = carry(o.backdrop, File(context.filesDir, "backgrounds"))
        fun bake(v: ThemeVariant): ThemeVariant {
            val k = v.keys
            val keys = k.copy(
                look = o.look ?: k.look,
                shape = o.shape ?: k.shape,
                font = o.font ?: k.font,
                labelCase = o.labelCase ?: k.labelCase,
                labelScale = (k.labelScale * o.labelScale).coerceIn(0.6f, 1.6f),
                opacity = (k.opacity * o.keyOpacity).coerceIn(0.1f, 1f),
                hints = k.hints && o.hints,
            )
            val bg = if (o.backdrop != null) userBg else carry(v.backdrop, themeDir)
            return v.copy(keys = keys, backdrop = bg)
        }
        val out = spec.copy(light = bake(spec.light), dark = spec.dark?.let { bake(it) })

        val dir = File(context.cacheDir, "shared_themes").apply { deleteRecursively(); mkdirs() }
        val name = spec.name.filter { it.isLetterOrDigit() || it == ' ' || it == '-' }.trim()
            .replace(' ', '_').take(40).ifEmpty { "theme" }
        val file = File(dir, name + EXTENSION)
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(JSON))
            zip.write(ThemeStore.json.encodeToString(ThemeSpec.serializer(), out).toByteArray())
            zip.closeEntry()
            for ((entry, f) in images) {
                zip.putNextEntry(ZipEntry(entry))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return file
    }

    /** Reads a .boritheme (or a bare theme.json) from [uri] into Mine. Returns the new theme. */
    fun import(context: Context, uri: Uri): Result<ThemeSpec> = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { readCapped(it, MAX_IMAGE * 2L) }
            ?: error("Couldn't open the file")
        val isZip = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        var json: String? = null
        val images = HashMap<String, ByteArray>()
        if (isZip) {
            ZipInputStream(bytes.inputStream()).use { zip ->
                var count = 0
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (++count > MAX_ENTRIES) error("Too many files inside")
                    val n = e.name
                    if (e.isDirectory || n.contains('/') || n.contains('\\') || n.contains("..")) continue
                    when {
                        n == JSON -> json = String(readCapped(zip, MAX_JSON.toLong()))
                        IMAGE_EXT.any { n.lowercase().endsWith(it) } -> images[n] = readCapped(zip, MAX_IMAGE.toLong())
                    }
                }
            }
        } else {
            if (bytes.size > MAX_JSON) error("Not a theme file")
            json = String(bytes)
        }
        val text = json ?: error("No theme inside this file")
        val parsed = runCatching { ThemeStore.decode(text) }.getOrElse { error("This theme file is damaged") }

        val store = ThemeStore(context)
        val id = "my_" + System.currentTimeMillis()
        val dir = store.dirOf(id).apply { mkdirs() }
        fun keepPhoto(b: BackgroundSpec?): BackgroundSpec? {
            if (b == null || b.type != "photo") return b
            val n = b.image?.let { File(it).name } ?: return null
            val data = images[n] ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val f = File(dir, n)
            if (!f.exists()) f.writeBytes(data)
            return b.copy(image = n)
        }
        val spec = parsed.copy(
            id = id,
            name = parsed.name.trim().take(40).ifEmpty { "Shared theme" },
            author = parsed.author.trim().take(40),
            category = "Mine",
            light = parsed.light.copy(backdrop = keepPhoto(parsed.light.backdrop)),
            dark = parsed.dark?.let { it.copy(backdrop = keepPhoto(it.backdrop)) },
        )
        runCatching { store.save(spec) }.onFailure { dir.deleteRecursively(); throw it }
        spec
    }.onFailure { DiagLog.event("theme import failed: $it") }

    private fun readCapped(input: InputStream, max: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > max) error("File is too big")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
