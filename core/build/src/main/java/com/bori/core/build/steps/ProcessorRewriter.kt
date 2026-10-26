package com.bori.core.build.steps

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Symbol processors are built for a desktop JDK and run inside the on-device compiler on ART.
 * Before one is dexed, its calls to JDK methods that Android lacks (and D8 doesn't backport) are
 * pointed at static helpers in com.bori.compat.ProcessorCompat (bundled in processor-stubs-dex.jar):
 * Java 9's trySetAccessible(), java.beans.Introspector.decapitalize and java.awt.EventQueue.
 */
object ProcessorRewriter {

    /** Bumped when the rewrite changes, so cached processor dex is redone. */
    const val VERSION = 2

    private const val COMPAT = "com/bori/compat/ProcessorCompat"
    private val REFLECTION_OWNERS = setOf(
        "java/lang/reflect/AccessibleObject", "java/lang/reflect/Constructor",
        "java/lang/reflect/Method", "java/lang/reflect/Field", "java/lang/reflect/Executable",
    )

    /** Static JDK methods → their ProcessorCompat stand-in (same descriptor). copyBuildTools has the same list. */
    private val STATICS = mapOf(
        "java/beans/Introspector.decapitalize(Ljava/lang/String;)Ljava/lang/String;" to "decapitalize",
        "java/awt/EventQueue.isDispatchThread()Z" to "isDispatchThread",
        "java/awt/EventQueue.invokeLater(Ljava/lang/Runnable;)V" to "invokeLater",
        "java/awt/EventQueue.invokeAndWait(Ljava/lang/Runnable;)V" to "invokeAndWait",
    )
    private val MARKERS = listOf("trySetAccessible", "java/beans/Introspector", "java/awt/EventQueue")

    /** Writes [jar] to [out] with its classes rewritten; returns how many classes changed. */
    fun rewrite(jar: File, out: File): Int {
        var changed = 0
        ZipFile(jar).use { zip ->
            ZipOutputStream(out.outputStream().buffered()).use { zos ->
                for (entry in zip.entries()) {
                    if (entry.isDirectory) continue
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    val result = if (entry.name.endsWith(".class")) rewriteClass(bytes) else null
                    if (result != null) changed++
                    zos.putNextEntry(ZipEntry(entry.name))
                    zos.write(result ?: bytes)
                    zos.closeEntry()
                }
            }
        }
        return changed
    }

    /** The rewritten class, or null when it calls none of the methods. */
    private fun rewriteClass(bytes: ByteArray): ByteArray? {
        // Cheap check first: most classes don't mention any of them.
        val text = String(bytes, Charsets.ISO_8859_1)
        if (MARKERS.none { it in text }) return null
        val reader = ClassReader(bytes)
        val writer = ClassWriter(reader, 0)
        var touched = false
        reader.accept(object : ClassVisitor(Opcodes.ASM9, writer) {
            override fun visitMethod(access: Int, name: String?, descriptor: String?, signature: String?, exceptions: Array<out String>?): MethodVisitor {
                val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
                return object : MethodVisitor(Opcodes.ASM9, mv) {
                    override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
                        val static = STATICS["$owner.$name$descriptor"]
                        when {
                            opcode == Opcodes.INVOKEVIRTUAL && owner in REFLECTION_OWNERS && name == "trySetAccessible" && descriptor == "()Z" -> {
                                touched = true
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, COMPAT, "trySetAccessible", "(Ljava/lang/reflect/AccessibleObject;)Z", false)
                            }
                            opcode == Opcodes.INVOKESTATIC && static != null -> {
                                touched = true
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, COMPAT, static, descriptor, false)
                            }
                            else -> super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                        }
                    }
                }
            }
        }, 0)
        return if (touched) writer.toByteArray() else null
    }
}
