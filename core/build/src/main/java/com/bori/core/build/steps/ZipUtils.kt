package com.bori.core.build.steps

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry

internal data class ZipEntryData(val name: String, val data: ByteArray, val method: Int)

/**
 * Writes a ZIP file with correct compression and 4-byte alignment for STORED entries.
 * Android 9+ requires resources.arsc to be STORED (not DEFLATE) and 4-byte aligned.
 * Alignment is achieved by padding the extra field in the local file header.
 */
internal fun writeAlignedZip(outputFile: File, entries: List<ZipEntryData>) {
    val buf = ByteArrayOutputStream()

    data class Written(
        val offset: Int,
        val crc: Long,
        val compressedSize: Int,
        val uncompressedSize: Int,
        val method: Int,
        val nameBytes: ByteArray,
    )
    val written = mutableListOf<Written>()

    for (entry in entries) {
        val nameBytes = entry.name.toByteArray(Charsets.UTF_8)
        val crc = CRC32().also { it.update(entry.data) }.value

        val compressedData: ByteArray
        val extraBytes: ByteArray

        if (entry.method == ZipEntry.STORED) {
            compressedData = entry.data
            // Padding so data starts at a 4-byte boundary
            val baseOffset = buf.size() + 30 + nameBytes.size
            val padding = ((4 - baseOffset % 4) % 4).toInt()
            extraBytes = ByteArray(padding)
        } else {
            compressedData = deflateRaw(entry.data)
            extraBytes = ByteArray(0)
        }

        val offset = buf.size()
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

    val cdOffset = buf.size()
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
        writeLeInt(buf, w.offset)
        buf.write(w.nameBytes)
    }
    val cdSize = buf.size() - cdOffset

    writeLeInt(buf, 0x06054b50)
    writeLeShort(buf, 0); writeLeShort(buf, 0)
    writeLeShort(buf, written.size); writeLeShort(buf, written.size)
    writeLeInt(buf, cdSize)
    writeLeInt(buf, cdOffset)
    writeLeShort(buf, 0)

    outputFile.writeBytes(buf.toByteArray())
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

private fun writeLeInt(out: ByteArrayOutputStream, v: Int) {
    out.write(v and 0xff)
    out.write((v ushr 8) and 0xff)
    out.write((v ushr 16) and 0xff)
    out.write((v ushr 24) and 0xff)
}

private fun writeLeShort(out: ByteArrayOutputStream, v: Int) {
    out.write(v and 0xff)
    out.write((v ushr 8) and 0xff)
}
