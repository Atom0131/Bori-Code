package com.bori.core.build.steps

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Merges library manifests into the app's, the parts AGP's manifest merger does that matter for
 * an APK. Uses org.w3c.dom, no external deps.
 *
 * - Top-level `<uses-permission>`, `<uses-permission-sdk-23>`, `<uses-feature>`, `<permission>`
 *   and `<queries>` children are added, deduplicated by android:name (or by content for queries).
 * - `<application>` children (activities, services, receivers, providers, meta-data,
 *   uses-library…) are added. When the app or an earlier library already has one with the same
 *   android:name, the two are merged: attributes it lacks are added and children are merged the
 *   same way (that's how androidx.startup's one InitializationProvider collects every library's
 *   `<meta-data>`). The app's own values always win.
 * - Relative class names in a library (".Foo") are made absolute with that library's package,
 *   and `${applicationId}` is replaced everywhere.
 * - An app element with `tools:node="remove"` removes that element; every `tools:` attribute and
 *   the tools namespace are stripped from the result.
 */
object ManifestMerger {

    private val TOP_LEVEL = setOf("uses-permission", "uses-permission-sdk-23", "uses-feature", "permission", "permission-group", "permission-tree")
    private val CLASS_ATTRS = listOf("android:name", "android:targetActivity", "android:backupAgent", "android:manageSpaceActivity")
    private val COMPONENTS = setOf("activity", "activity-alias", "service", "receiver", "provider", "application")

    fun merge(
        appManifest: File,
        libraries: List<LibraryManifest>,
        outputFile: File,
        applicationId: String,
    ) {
        val db = DocumentBuilderFactory.newInstance().also { it.isNamespaceAware = false }.newDocumentBuilder()
        val doc = db.parse(appManifest)
        val manifest = doc.documentElement
        val application = childElements(manifest).firstOrNull { it.nodeName == "application" }
            ?: doc.createElement("application").also { manifest.appendChild(it) }

        // Elements the app removes with tools:node="remove", by tag + name.
        val removed = mutableSetOf<String>()
        for (el in descendants(manifest)) {
            if (el.getAttribute("tools:node") == "remove") removed += key(el)
        }

        for (lib in libraries) {
            if (!lib.file.isFile) continue
            val libDoc = try { db.parse(lib.file) } catch (_: Exception) { continue }
            val libManifest = libDoc.documentElement
            qualifyClassNames(libManifest, lib.packageName)
            for (el in childElements(libManifest)) {
                when (el.nodeName) {
                    in TOP_LEVEL -> if (key(el) !in removed) mergeInto(doc, manifest, el, removed, beforeApplication = application)
                    "queries" -> mergeQueries(doc, manifest, el, application)
                    "application" -> for (child in childElements(el)) {
                        if (key(child) !in removed) mergeInto(doc, application, child, removed)
                    }
                }
            }
        }

        // Apply removals, then drop tools: markup and resolve placeholders.
        for (el in descendants(manifest).toList()) {
            if (el.getAttribute("tools:node") == "remove") el.parentNode.removeChild(el)
        }
        for (el in descendants(manifest)) {
            val attrs = el.attributes
            for (i in attrs.length - 1 downTo 0) {
                val attr = attrs.item(i)
                when {
                    attr.nodeName.startsWith("tools:") || attr.nodeName == "xmlns:tools" -> el.removeAttribute(attr.nodeName)
                    attr.nodeValue.contains("\${applicationId}") -> attr.nodeValue = attr.nodeValue.replace("\${applicationId}", applicationId)
                }
            }
        }

        val transformer = TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty(OutputKeys.INDENT, "no")
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
        }
        outputFile.outputStream().use { os -> transformer.transform(DOMSource(doc), StreamResult(os)) }
    }

    /**
     * Adds [incoming] under [parent], or merges it into the element with the same key: missing
     * attributes are copied and children merged recursively. New top-level elements go before
     * [beforeApplication], as AAPT2 expects.
     */
    private fun mergeInto(doc: Document, parent: Element, incoming: Element, removed: Set<String>, beforeApplication: Node? = null) {
        val existing = childElements(parent).firstOrNull { key(it) == key(incoming) && hasName(it) }
        if (existing == null) {
            val copy = doc.importNode(incoming, true)
            if (beforeApplication != null) parent.insertBefore(copy, beforeApplication) else parent.appendChild(copy)
            return
        }
        if (existing.getAttribute("tools:node") == "replace") return
        val attrs = incoming.attributes
        for (i in 0 until attrs.length) {
            val attr = attrs.item(i)
            if (!existing.hasAttribute(attr.nodeName)) existing.setAttribute(attr.nodeName, attr.nodeValue)
        }
        for (child in childElements(incoming)) {
            if (key(child) in removed) continue
            if (hasName(child)) mergeInto(doc, existing, child, removed)
            else if (childElements(existing).none { it.isEqualNode(child) }) existing.appendChild(doc.importNode(child, true))
        }
    }

    /** `<queries>` children (packages, intents, providers) are added unless the app already has an identical one. */
    private fun mergeQueries(doc: Document, manifest: Element, incoming: Element, application: Node) {
        val queries = childElements(manifest).firstOrNull { it.nodeName == "queries" }
            ?: doc.createElement("queries").also { manifest.insertBefore(it, application) }
        for (child in childElements(incoming)) {
            val copy = doc.importNode(child, true)
            if (childElements(queries).none { it.isEqualNode(copy) }) queries.appendChild(copy)
        }
    }

    /** Turns ".Foo" and "Foo" class names on components into "<package>.Foo". */
    private fun qualifyClassNames(root: Element, packageName: String) {
        if (packageName.isEmpty()) return
        for (el in descendants(root)) {
            if (el.nodeName !in COMPONENTS) continue
            for (attr in CLASS_ATTRS) {
                val value = el.getAttribute(attr)
                if (value.isEmpty() || value.startsWith("\${")) continue
                when {
                    value.startsWith(".") -> el.setAttribute(attr, packageName + value)
                    !value.contains('.') -> el.setAttribute(attr, "$packageName.$value")
                }
            }
        }
    }

    private fun hasName(el: Element) = el.getAttribute("android:name").isNotEmpty()

    private fun key(el: Element) = el.nodeName + "|" + el.getAttribute("android:name")

    private fun childElements(el: Element): List<Element> {
        val nodes = el.childNodes
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    private fun descendants(el: Element): Sequence<Element> = sequence {
        yield(el)
        for (child in childElements(el)) yieldAll(descendants(child))
    }
}
