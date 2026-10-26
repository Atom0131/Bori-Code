package com.bori.core.build.steps

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class ApkPackager {

    /**
     * Packs the AAPT2 resource APK with [dexFiles] (as classes.dex, classes2.dex, … in order),
     * the files in [assetsDirs] (under assets/) and the native libraries in [nativeLibDirs]
     * (`<abi>/lib*.so`, under lib/). For assets and native libraries a later directory overrides
     * an earlier one, so pass libraries first and the app last.
     */
    fun pack(
        aapt2Apk: File,
        dexFiles: List<File>,
        outputApk: File,
        emit: (String) -> Unit,
        assetsDirs: List<File> = emptyList(),
        nativeLibDirs: List<File> = emptyList(),
    ) {
        val entries = mutableListOf<ZipEntryData>()
        ZipInputStream(aapt2Apk.inputStream().buffered()).use { zin ->
            var ze = zin.nextEntry
            while (ze != null) {
                entries.add(ZipEntryData(ze.name, zin.readBytes(), ze.method))
                ze = zin.nextEntry
            }
        }

        dexFiles.forEachIndexed { index, dexFile ->
            val entryName = if (index == 0) "classes.dex" else "classes${index + 1}.dex"
            entries.add(ZipEntryData(entryName, dexFile.readBytes(), ZipEntry.DEFLATED))
        }

        val assets = linkedMapOf<String, File>()
        for (dir in assetsDirs.filter { it.isDirectory }) {
            dir.walkTopDown().filter { it.isFile }.forEach { assets["assets/" + it.relativeTo(dir).invariantSeparatorsPath] = it }
        }
        assets.forEach { (name, file) ->
            val method = if (file.extension.lowercase() in NO_COMPRESS) ZipEntry.STORED else ZipEntry.DEFLATED
            entries.add(ZipEntryData(name, file.readBytes(), method))
        }

        // Stored uncompressed (and 16 KB aligned by writeAlignedZip) so they load straight from the APK.
        val libs = linkedMapOf<String, File>()
        for (dir in nativeLibDirs.filter { it.isDirectory }) {
            dir.listFiles { f -> f.isDirectory && f.name in ABIS }?.forEach { abiDir ->
                abiDir.listFiles { f -> f.isFile && f.extension == "so" }?.forEach { libs["lib/${abiDir.name}/${it.name}"] = it }
            }
        }
        libs.forEach { (name, file) -> entries.add(ZipEntryData(name, file.readBytes(), ZipEntry.STORED)) }

        emit(buildString {
            append("Packaging APK from ${aapt2Apk.name} + ${dexFiles.size} dex file(s)")
            if (assets.isNotEmpty()) append(" + ${assets.size} asset(s)")
            if (libs.isNotEmpty()) append(" + ${libs.size} native lib(s) (${libs.keys.map { it.split('/')[1] }.distinct().joinToString()})")
        })
        writeAlignedZip(outputApk, entries)
        emit("Packed APK: ${outputApk.length() / 1024} KB")
    }

    private companion object {
        val ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64", "riscv64")

        /** Already-compressed formats AGP stores as-is (its default noCompress list). */
        val NO_COMPRESS = setOf(
            "jpg", "jpeg", "png", "gif", "webp", "wav", "mp2", "mp3", "ogg", "aac", "mpg", "mpeg", "mid", "midi",
            "smf", "jet", "rtttl", "imy", "xmf", "mp4", "m4a", "m4v", "3gp", "3gpp", "3g2", "3gpp2", "amr", "awb",
            "wma", "wmv", "webm", "mkv", "zip", "gz", "tflite",
        )
    }
}
