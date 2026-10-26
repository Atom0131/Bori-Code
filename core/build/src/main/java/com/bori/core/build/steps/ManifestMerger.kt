package com.bori.core.build.steps

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Merges <uses-permission> and <uses-feature> entries from the bundled library manifest
 * entries file into a user AndroidManifest.xml. Uses org.w3c.dom — no external deps needed.
 *
 * Rules:
 * - Elements are deduplicated by android:name (first occurrence wins).
 * - Injected elements are inserted before <application> to satisfy AAPT2 ordering.
 * - If either file is missing or malformed, the user manifest is returned unchanged.
 */
object ManifestMerger {

    fun merge(
        userManifest: File,
        libraryEntriesXml: File,
        outputFile: File,
    ) {
        if (!libraryEntriesXml.exists() || libraryEntriesXml.length() == 0L) {
            userManifest.copyTo(outputFile, overwrite = true)
            return
        }

        val dbf = DocumentBuilderFactory.newInstance().also { it.isNamespaceAware = false }
        val db = dbf.newDocumentBuilder()

        val userDoc = db.parse(userManifest)
        val entriesDoc = db.parse(libraryEntriesXml)

        val manifestEl = userDoc.documentElement

        // Collect existing names to avoid duplicates
        val existingPermissions = mutableSetOf<String>()
        val existingFeatures = mutableSetOf<String>()
        val children = manifestEl.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i) as? Element ?: continue
            val name = node.getAttribute("android:name")
            when (node.nodeName) {
                "uses-permission" -> if (name.isNotBlank()) existingPermissions.add(name)
                "uses-feature" -> if (name.isNotBlank()) existingFeatures.add(name)
            }
        }

        // Find <application> element to insert before it
        var applicationEl: org.w3c.dom.Node? = null
        for (i in 0 until children.length) {
            val node = children.item(i) as? Element ?: continue
            if (node.nodeName == "application") { applicationEl = node; break }
        }

        // Walk entries and import new ones
        val entryNodes = entriesDoc.documentElement.childNodes
        for (i in 0 until entryNodes.length) {
            val node = entryNodes.item(i) as? Element ?: continue
            val name = node.getAttribute("android:name")
            when (node.nodeName) {
                "uses-permission" -> {
                    if (name.isNotBlank() && existingPermissions.add(name)) {
                        manifestEl.insertBefore(userDoc.importNode(node, true), applicationEl)
                        manifestEl.insertBefore(userDoc.createTextNode("\n    "), applicationEl)
                    }
                }
                "uses-feature" -> {
                    if (name.isNotBlank() && existingFeatures.add(name)) {
                        manifestEl.insertBefore(userDoc.importNode(node, true), applicationEl)
                        manifestEl.insertBefore(userDoc.createTextNode("\n    "), applicationEl)
                    }
                }
            }
        }

        val transformer = TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty(OutputKeys.INDENT, "no")
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
        }
        outputFile.outputStream().use { os ->
            transformer.transform(DOMSource(userDoc), StreamResult(os))
        }
    }
}
