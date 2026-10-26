package com.bori.core.build.steps

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class ApkPackager {

    fun pack(
        aapt2Apk: File,
        classesDex: File,
        outputApk: File,
        emit: (String) -> Unit,
        libraryDex: File? = null,
    ) {
        // Collect all DEX files from D8's output directory (multidex: classes.dex, classes2.dex, …).
        val dexDir = classesDex.parentFile
        val userDexFiles = dexDir?.listFiles { f -> f.name.matches(Regex("classes\\d*\\.dex")) }
            ?.sortedWith(compareBy { if (it.name == "classes.dex") 0 else it.name.removePrefix("classes").removeSuffix(".dex").toIntOrNull() ?: Int.MAX_VALUE })
            ?: listOf(classesDex)

        val haslibDex = libraryDex != null && libraryDex.exists()
        emit("Packaging APK from ${aapt2Apk.name} + ${userDexFiles.size} dex file(s)${if (haslibDex) " + library dex" else ""}")

        val entries = mutableListOf<ZipEntryData>()
        ZipInputStream(aapt2Apk.inputStream().buffered()).use { zin ->
            var ze = zin.nextEntry
            while (ze != null) {
                entries.add(ZipEntryData(ze.name, zin.readBytes(), ze.method))
                ze = zin.nextEntry
            }
        }

        // Add all user DEX files with correct APK entry names.
        userDexFiles.forEachIndexed { index, dexFile ->
            val entryName = if (index == 0) "classes.dex" else "classes${index + 1}.dex"
            entries.add(ZipEntryData(entryName, dexFile.readBytes(), ZipEntry.DEFLATED))
        }
        // Add pre-compiled library DEX as the next sequential entry.
        if (haslibDex) {
            val libEntryName = if (userDexFiles.size == 1) "classes2.dex" else "classes${userDexFiles.size + 1}.dex"
            entries.add(ZipEntryData(libEntryName, libraryDex!!.readBytes(), ZipEntry.DEFLATED))
        }

        writeAlignedZip(outputApk, entries)
        emit("Packed APK: ${outputApk.length() / 1024} KB")
    }
}
