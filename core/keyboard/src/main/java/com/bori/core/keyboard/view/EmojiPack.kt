package com.bori.core.keyboard.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.bori.core.keyboard.ime.DiagLog

/**
 * One emoji style pack from assets/emoji/<id>.bep (built by tools/build_emoji_packs.py):
 * small WebP images keyed by the emoji without U+FE0F. Only the look in Bori's emoji panel
 * changes; the text sent to the app is the same emoji character.
 */
class EmojiPack private constructor(val id: String, private val data: ByteArray) {

    private val index = HashMap<String, Long>()
    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    init {
        require(data.size >= 8 && String(data, 0, 4, Charsets.US_ASCII) == "BEP1") { "not a .bep file" }
        val count = int(4)
        var p = 8
        repeat(count) {
            val len = data[p].toInt() and 0xFF
            val key = String(data, p + 1, len, Charsets.UTF_8)
            p += 1 + len
            index[key] = (int(p).toLong() shl 32) or (int(p + 4).toLong() and 0xFFFFFFFFL)
            p += 8
        }
    }

    private fun int(at: Int) = (data[at].toInt() and 0xFF) or
        ((data[at + 1].toInt() and 0xFF) shl 8) or
        ((data[at + 2].toInt() and 0xFF) shl 16) or
        ((data[at + 3].toInt() and 0xFF) shl 24)

    fun has(emoji: String) = index.containsKey(keyOf(emoji))

    /** The emoji's picture, or null when this pack doesn't have it (draw it as text then). */
    fun bitmap(emoji: String): Bitmap? {
        val k = keyOf(emoji)
        cache.get(k)?.let { return it }
        val entry = index[k] ?: return null
        val offset = (entry ushr 32).toInt()
        val length = (entry and 0xFFFFFFFFL).toInt()
        val bmp = runCatching { BitmapFactory.decodeByteArray(data, offset, length) }.getOrNull() ?: return null
        cache.put(k, bmp)
        return bmp
    }

    companion object {
        /** "system" = the phone's own emoji font (no pack). */
        const val SYSTEM = "system"
        const val DEFAULT = "twemoji"

        /** id to name shown in Settings, and credit line. */
        val STYLES = listOf(
            Triple("twemoji", "Twemoji", "Twemoji by Twitter and contributors, CC BY 4.0"),
            Triple("fluent", "Fluent", "Fluent Emoji by Microsoft, MIT License"),
            Triple("openmoji", "OpenMoji", "OpenMoji, the open-source emoji project, CC BY-SA 4.0"),
            Triple("noto", "Noto", "Noto Emoji by Google, Apache License 2.0"),
            Triple("blobmoji", "Blobs", "Blobmoji (Google's old blob emoji), Apache License 2.0"),
            Triple("emojione2", "EmojiOne 2", "EmojiOne 2.2 artwork, CC BY 4.0 (newer emoji use your phone's font)"),
            Triple("fxemoji", "Firefox", "FxEmoji by Mozilla, CC BY 4.0 (newer emoji use your phone's font)"),
            Triple(SYSTEM, "Phone default", "Your phone's own emoji font"),
        )

        private const val CACHE_BYTES = 6 * 1024 * 1024

        private var loaded: EmojiPack? = null
        private var customLoaded = false
        private var customPack: EmojiPack? = null

        /**
         * Bori's own pictures that win over every style, including the phone's font
         * (assets/emoji/custom.bep): the Puerto Rico flag with the light-blue triangle.
         */
        @Synchronized
        fun custom(context: Context): EmojiPack? {
            if (!customLoaded) {
                customLoaded = true
                customPack = runCatching {
                    EmojiPack("custom", context.assets.open("emoji/custom.bep").use { it.readBytes() })
                }.getOrNull()
            }
            return customPack
        }

        fun keyOf(emoji: String) = emoji.replace("\uFE0F", "")

        /** The pack for [id] (only one is kept in memory), or null for the phone's font or on error. */
        @Synchronized
        fun get(context: Context, id: String): EmojiPack? {
            if (id == SYSTEM) return null
            loaded?.let { if (it.id == id) return it }
            return try {
                val bytes = context.assets.open("emoji/$id.bep").use { it.readBytes() }
                EmojiPack(id, bytes).also { loaded = it }
            } catch (t: Throwable) {
                DiagLog.event("emoji pack $id failed: $t")
                null
            }
        }
    }
}
