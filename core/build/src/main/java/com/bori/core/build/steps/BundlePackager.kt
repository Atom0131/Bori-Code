package com.bori.core.build.steps

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Packs an Android App Bundle (.aab), the format Google Play takes, with a single `base` module:
 *
 * ```
 * BundleConfig.pb
 * base/manifest/AndroidManifest.xml   (proto XML from aapt2 link --proto-format)
 * base/resources.pb                   (proto resource table)
 * base/res/…                          (proto-compiled resources)
 * base/dex/classes.dex, classes2.dex…
 * base/assets/…
 * base/lib/<abi>/lib*.so
 * ```
 *
 * Play (or bundletool) turns it into the APKs each device installs.
 */
class BundlePackager {

    fun pack(
        protoResApk: File,
        dexFiles: List<File>,
        outputAab: File,
        emit: (String) -> Unit,
        assetsDirs: List<File> = emptyList(),
        nativeLibDirs: List<File> = emptyList(),
    ) {
        val entries = mutableListOf(ZipEntryData("BundleConfig.pb", bundleConfig(), ZipEntry.DEFLATED))
        ZipInputStream(protoResApk.inputStream().buffered()).use { zin ->
            var ze = zin.nextEntry
            while (ze != null) {
                if (!ze.isDirectory) {
                    val name = when (ze.name) {
                        "AndroidManifest.xml" -> "base/manifest/AndroidManifest.xml"
                        else -> "base/${ze.name}" // resources.pb, res/…
                    }
                    entries += ZipEntryData(name, zin.readBytes(), ZipEntry.DEFLATED)
                }
                ze = zin.nextEntry
            }
        }
        dexFiles.forEachIndexed { index, dex ->
            val name = if (index == 0) "classes.dex" else "classes${index + 1}.dex"
            entries += ZipEntryData("base/dex/$name", dex.readBytes(), ZipEntry.DEFLATED)
        }
        val assets = linkedMapOf<String, File>()
        for (dir in assetsDirs.filter { it.isDirectory }) {
            dir.walkTopDown().filter { it.isFile }.forEach { assets["base/assets/" + it.relativeTo(dir).invariantSeparatorsPath] = it }
        }
        assets.forEach { (name, file) -> entries += ZipEntryData(name, file.readBytes(), ZipEntry.DEFLATED) }
        val libs = linkedMapOf<String, File>()
        for (dir in nativeLibDirs.filter { it.isDirectory }) {
            dir.listFiles { f -> f.isDirectory }?.forEach { abiDir ->
                abiDir.listFiles { f -> f.isFile && f.extension == "so" }?.forEach { libs["base/lib/${abiDir.name}/${it.name}"] = it }
            }
        }
        libs.forEach { (name, file) -> entries += ZipEntryData(name, file.readBytes(), ZipEntry.DEFLATED) }

        emit("Packaging app bundle: ${dexFiles.size} dex file(s), ${assets.size} asset(s), ${libs.size} native lib(s)")
        writeAlignedZip(outputAab, entries)
        emit("Packed app bundle: ${outputAab.length() / 1024} KB")
    }

    /**
     * BundleConfig.pb: `bundletool { version: "…" }` (field 1, with the version in its field 2),
     * the one field Play requires. Protobuf wire format by hand; it's two length-prefixed strings.
     */
    private fun bundleConfig(): ByteArray {
        val version = BUNDLETOOL_VERSION.toByteArray(Charsets.UTF_8)
        val bundletool = byteArrayOf(0x12, version.size.toByte()) + version
        return byteArrayOf(0x0A, bundletool.size.toByte()) + bundletool
    }

    private companion object {
        /** The bundletool version the bundle says it follows (its format, not a tool we ship). */
        const val BUNDLETOOL_VERSION = "1.17.2"
    }
}
