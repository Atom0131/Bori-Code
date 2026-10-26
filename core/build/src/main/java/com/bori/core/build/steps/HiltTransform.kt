package com.bori.core.build.steps

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Does what the Hilt Gradle plugin's bytecode transform does for the common case: a class
 * annotated `@AndroidEntryPoint` or `@HiltAndroidApp` that extends, say, `AppCompatActivity`
 * is made to extend the generated `Hilt_MainActivity` instead (which itself extends
 * `AppCompatActivity` and does the injection), so projects written for the plugin work as is.
 *
 * The class file's superclass constant is pointed at a new `Hilt_…` name. Every reference that
 * went through that constant (the super constructor and super.onCreate() calls) then goes to the
 * generated class, which inherits everything the old superclass had.
 *
 * BroadcastReceivers aren't handled: the plugin also inserts a super.onReceive() call for them.
 */
object HiltTransform {

    private val MARKERS = setOf("Ldagger/hilt/android/AndroidEntryPoint;", "Ldagger/hilt/android/HiltAndroidApp;")

    /** Rewrites the entry points in [classesDir]; returns how many classes changed. */
    fun apply(classesDir: File, emit: (String) -> Unit): Int {
        var changed = 0
        classesDir.walkTopDown()
            .filter { it.isFile && it.extension == "class" && !it.name.startsWith("Hilt_") }
            .forEach { file ->
                val bytes = file.readBytes()
                val info = runCatching { parse(bytes) }.getOrNull() ?: return@forEach
                if (info.utf8.values.none { it in MARKERS }) return@forEach
                val name = info.utf8[info.classNameIndex[info.thisClass]] ?: return@forEach
                val superName = info.utf8[info.classNameIndex[info.superClass]] ?: return@forEach
                val pkg = name.substringBeforeLast('/', "")
                val hiltName = (if (pkg.isEmpty()) "" else "$pkg/") + "Hilt_" + name.substringAfterLast('/').replace('$', '_')
                if (superName == hiltName || !File(classesDir, "$hiltName.class").isFile) return@forEach
                if (superName == "android/content/BroadcastReceiver") {
                    emit("⚠ Hilt: ${name.replace('/', '.')} is a BroadcastReceiver; make it extend Hilt_${name.substringAfterLast('/')} yourself")
                    return@forEach
                }
                file.writeBytes(rewriteSuper(bytes, info, hiltName))
                emit("Hilt: ${name.replace('/', '.')} now extends ${hiltName.replace('/', '.')}")
                changed++
            }
        return changed
    }

    private class ClassInfo(
        val poolCount: Int,
        /** Constant index → Utf8 text. */
        val utf8: Map<Int, String>,
        /** Constant index of a Class entry → its name's Utf8 index. */
        val classNameIndex: Map<Int, Int>,
        /** Byte offset of each Class entry's name_index (after the tag byte). */
        val classNameOffset: Map<Int, Int>,
        /** Byte offset where the constant pool ends. */
        val poolEnd: Int,
        val thisClass: Int,
        val superClass: Int,
    )

    private fun parse(bytes: ByteArray): ClassInfo? {
        val input = DataInputStream(bytes.inputStream())
        if (input.readInt() != 0xCAFEBABE.toInt()) return null
        input.readUnsignedShort(); input.readUnsignedShort() // minor, major
        val count = input.readUnsignedShort()
        var offset = 10
        val utf8 = mutableMapOf<Int, String>()
        val classNames = mutableMapOf<Int, Int>()
        val classOffsets = mutableMapOf<Int, Int>()
        var i = 1
        while (i < count) {
            val tag = input.readUnsignedByte()
            offset += 1
            when (tag) {
                1 -> {
                    val len = input.readUnsignedShort()
                    val data = ByteArray(len).also { input.readFully(it) }
                    utf8[i] = String(data, Charsets.UTF_8) // modified UTF-8; identifiers here are ASCII
                    offset += 2 + len
                }
                7 -> { classOffsets[i] = offset; classNames[i] = input.readUnsignedShort(); offset += 2 }
                8, 16, 19, 20 -> { input.readUnsignedShort(); offset += 2 }
                3, 4, 9, 10, 11, 12, 17, 18 -> { input.readInt(); offset += 4 }
                15 -> { input.readUnsignedByte(); input.readUnsignedShort(); offset += 3 }
                5, 6 -> { input.readLong(); offset += 8; i++ } // takes two slots
                else -> return null
            }
            i++
        }
        input.readUnsignedShort() // access flags
        val thisClass = input.readUnsignedShort()
        val superClass = input.readUnsignedShort()
        return ClassInfo(count, utf8, classNames, classOffsets, offset, thisClass, superClass)
    }

    /** Appends a Utf8 constant with [newSuper] and points the superclass's Class constant at it. */
    private fun rewriteSuper(bytes: ByteArray, info: ClassInfo, newSuper: String): ByteArray {
        val patched = bytes.copyOf()
        val newIndex = info.poolCount // the new last entry
        val nameOffset = info.classNameOffset.getValue(info.superClass)
        patched[nameOffset] = (newIndex shr 8).toByte()
        patched[nameOffset + 1] = newIndex.toByte()

        val out = ByteArrayOutputStream(bytes.size + newSuper.length + 8)
        val data = DataOutputStream(out)
        data.write(patched, 0, 8)
        data.writeShort(info.poolCount + 1)
        data.write(patched, 10, info.poolEnd - 10)
        data.writeByte(1)
        data.writeUTF(newSuper)
        data.write(patched, info.poolEnd, patched.size - info.poolEnd)
        data.flush()
        return out.toByteArray()
    }
}
