package com.bori.core.build.steps

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry

internal data class ZipEntryData(val name: String, val data: ByteArray, val method: Int)

/** Native libraries are stored at 16 KB boundaries so they can be mapped straight from the APK on 16 KB-page devices. */
private const val SO_ALIGNMENT = 16384

/**
 * Writes a ZIP file with correct compression and alignment for STORED entries: 4 bytes, or
 * [SO_ALIGNMENT] for `.so` files. Android 9+ requires resources.arsc to be STORED (not DEFLATE)
 * and 4-byte aligned. Alignment is achieved by padding the extra field in the local file header.
 */
internal fun writeAlignedZip(outputFile: File, entries: List<ZipEntryData>) {
    data class Written(
        val offset: Long,
        val crc: Long,
        val compressedSize: Int,
        val uncompressedSize: Int,
        val method: Int,
        val nameBytes: ByteArray,
    )
    val written = mutableListOf<Written>()

    CountingStream(outputFile.outputStream().buffered()).use { buf ->
        for (entry in entries) {
            val nameBytes = entry.name.toByteArray(Charsets.UTF_8)
            val crc = CRC32().also { it.update(entry.data) }.value

            val compressedData: ByteArray
            val extraBytes: ByteArray

            if (entry.method == ZipEntry.STORED) {
                compressedData = entry.data
                // Padding so data starts at an aligned boundary
                val alignment = if (entry.name.endsWith(".so")) SO_ALIGNMENT else 4
                val baseOffset = buf.count + 30 + nameBytes.size
                val padding = ((alignment - baseOffset % alignment) % alignment).toInt()
                extraBytes = ByteArray(padding)
            } else {
                compressedData = deflateRaw(entry.data)
                extraBytes = ByteArray(0)
            }

            val offset = buf.count
            writeLeInt(buf, 0x04034b50)
            writeLeShort(buf, 20)
            writeLeShort(buf, 0)
            writeLeShort(buf, entry.method)
            writeLeShort(buf, 0); writeLeShort(buf, 0)
            writeLeInt(buf, crc.toInt())
            writeLeInt(buf, compressedData.size)
            writeLeInt(buf, entry.data.size)
            writeLeShort(buf, nameBytes.size)
            writeLeShort(buf, extraBytes.size)
            buf.write(nameBytes)
            buf.write(extraBytes)
            buf.write(compressedData)

            written.add(Written(offset, crc, compressedData.size, entry.data.size, entry.method, nameBytes))
        }

        val cdOffset = buf.count
        for (w in written) {
            writeLeInt(buf, 0x02014b50)
            writeLeShort(buf, 20); writeLeShort(buf, 20)
            writeLeShort(buf, 0)
            writeLeShort(buf, w.method)
            writeLeShort(buf, 0); writeLeShort(buf, 0)
            writeLeInt(buf, w.crc.toInt())
            writeLeInt(buf, w.compressedSize)
            writeLeInt(buf, w.uncompressedSize)
            writeLeShort(buf, w.nameBytes.size)
            writeLeShort(buf, 0); writeLeShort(buf, 0)
            writeLeShort(buf, 0); writeLeShort(buf, 0)
            writeLeInt(buf, 0)
            writeLeInt(buf, w.offset.toInt())
            buf.write(w.nameBytes)
        }
        val cdSize = buf.count - cdOffset

        writeLeInt(buf, 0x06054b50)
        writeLeShort(buf, 0); writeLeShort(buf, 0)
        writeLeShort(buf, written.size); writeLeShort(buf, written.size)
        writeLeInt(buf, cdSize.toInt())
        writeLeInt(buf, cdOffset.toInt())
        writeLeShort(buf, 0)
    }
}

/** Counts the bytes written, so entry offsets are known without buffering the whole ZIP. */
private class CountingStream(private val out: OutputStream) : OutputStream() {
    var count = 0L
        private set
    override fun write(b: Int) { out.write(b); count++ }
    override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
    override fun flush() = out.flush()
    override fun close() = out.close()
}

private fun deflateRaw(data: ByteArray): ByteArray {
    val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
    deflater.setInput(data)
    deflater.finish()
    val out = ByteArrayOutputStream()
    val tmp = ByteArray(65536)
    while (!deflater.finished()) out.write(tmp, 0, deflater.deflate(tmp))
    deflater.end()
    return out.toByteArray()
}

private fun writeLeInt(out: OutputStream, v: Int) {
    out.write(v and 0xff)
    out.write((v ushr 8) and 0xff)
    out.write((v ushr 16) and 0xff)
    out.write((v ushr 24) and 0xff)
}

private fun writeLeShort(out: OutputStream, v: Int) {
    out.write(v and 0xff)
    out.write((v ushr 8) and 0xff)
}
