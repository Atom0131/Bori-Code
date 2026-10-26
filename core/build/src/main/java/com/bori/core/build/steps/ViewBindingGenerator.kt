package com.bori.core.build.steps

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Generates View Binding classes (`<namespace>.databinding.ActivityMainBinding`) from a module's
 * layouts, as AGP does with `buildFeatures { viewBinding = true }`: one class per layout name, a
 * field per view with an `android:id`, and `inflate(...)` / `bind(...)` methods. The classes
 * implement `androidx.viewbinding.ViewBinding`, so the module needs `androidx.databinding:viewbinding`.
 *
 * A view that's missing from some of the layout's configurations (layout-land…) is @Nullable,
 * and one whose type differs between them is a plain View. `<include>` with an id becomes that
 * layout's binding; layouts marked `tools:viewBindingIgnore="true"` are skipped.
 */
object ViewBindingGenerator {

    private const val ANDROID = "http://schemas.android.com/apk/res/android"
    private const val TOOLS = "http://schemas.android.com/tools"

    private class ViewRef(val id: String, val type: String, val includeLayout: String?)
    private class Variant(val rootType: String, val isMerge: Boolean, val rootId: String?, val views: List<ViewRef>)

    /** Writes the binding classes for [resDir] into [outDir]; returns the files written. */
    fun generate(resDir: File, namespace: String, outDir: File): List<File> {
        val layouts = sortedMapOf<String, MutableList<Variant>>()
        resDir.listFiles { f -> f.isDirectory && (f.name == "layout" || f.name.startsWith("layout-")) }
            ?.sortedBy { it.name }
            ?.forEach { dir ->
                dir.listFiles { f -> f.isFile && f.extension == "xml" }?.forEach { file ->
                    parse(file)?.let { layouts.getOrPut(file.nameWithoutExtension) { mutableListOf() } += it }
                }
            }
        val pkg = "$namespace.databinding"
        val dir = File(outDir, pkg.replace('.', '/')).also { it.mkdirs() }
        return layouts.map { (layout, variants) ->
            File(dir, className(layout) + ".java").apply { writeText(source(pkg, namespace, layout, variants, layouts.keys)) }
        }
    }

    private fun parse(file: File): Variant? {
        val doc = runCatching {
            DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file)
        }.getOrNull() ?: return null
        val root = doc.documentElement
        if (root.getAttributeNS(TOOLS, "viewBindingIgnore") == "true") return null
        val isMerge = root.tagName == "merge"
        val views = mutableListOf<ViewRef>()
        fun walk(el: Element, isRoot: Boolean) {
            val id = idName(el.getAttributeNS(ANDROID, "id"))
            if (id != null && !isRoot) {
                when (el.tagName) {
                    "include" -> el.getAttribute("layout").removePrefix("@layout/").takeIf { it.isNotBlank() }
                        ?.let { views += ViewRef(id, "", it) }
                    "fragment", "requestFocus", "tag" -> Unit
                    else -> views += ViewRef(id, viewClass(el), null)
                }
            }
            val children = el.childNodes
            for (i in 0 until children.length) (children.item(i) as? Element)?.let { walk(it, false) }
        }
        walk(root, true)
        return Variant(
            rootType = if (isMerge) "android.view.View" else viewClass(root),
            isMerge = isMerge,
            rootId = idName(root.getAttributeNS(ANDROID, "id")),
            views = views,
        )
    }

    private fun idName(value: String?): String? =
        value?.takeIf { it.startsWith("@+id/") || it.startsWith("@id/") }?.substringAfter('/')

    private fun viewClass(el: Element): String {
        val tag = if (el.tagName == "view") el.getAttribute("class").ifBlank { "View" } else el.tagName
        return when {
            tag.contains('.') -> tag
            tag in setOf("View", "ViewGroup", "ViewStub", "SurfaceView", "TextureView") -> "android.view.$tag"
            tag == "WebView" -> "android.webkit.WebView"
            else -> "android.widget.$tag"
        }
    }

    private fun className(layout: String) = camel(layout, upperFirst = true) + "Binding"

    private fun camel(name: String, upperFirst: Boolean): String {
        val parts = name.split('_', '.', '-').filter { it.isNotEmpty() }
        return parts.mapIndexed { i, p ->
            if (i == 0 && !upperFirst) p.replaceFirstChar { it.lowercase() } else p.replaceFirstChar { it.uppercase() }
        }.joinToString("")
    }

    private fun source(pkg: String, namespace: String, layout: String, variants: List<Variant>, known: Set<String>): String {
        val cls = className(layout)
        val isMerge = variants.all { it.isMerge }
        val rootType = variants.map { it.rootType }.distinct().singleOrNull() ?: "android.view.View"
        class Field(val name: String, val id: String, val type: String, val nullable: Boolean, val include: String?)
        val ids = linkedSetOf<String>().apply { variants.forEach { v -> v.views.forEach { add(it.id) } } }
        val rootIds = variants.mapNotNull { it.rootId }.toSet()
        val fields = ids.filter { it !in rootIds }.map { id ->
            val refs = variants.mapNotNull { v -> v.views.firstOrNull { it.id == id } }
            val include = refs.map { it.includeLayout }.distinct().singleOrNull()?.takeIf { it in known }
            val type = when {
                include != null -> "$pkg.${className(include)}"
                else -> refs.map { it.type }.distinct().singleOrNull() ?: "android.view.View"
            }
            Field(camel(id, upperFirst = false), id, type, nullable = refs.size < variants.size, include = include)
        }
        val rootField = rootIds.singleOrNull()?.let { camel(it, upperFirst = false) }?.takeIf { name -> fields.none { it.name == name } }

        return buildString {
            appendLine("// Generated by Bori Code from res/layout/$layout.xml. Do not edit.")
            appendLine("package $pkg;")
            appendLine()
            appendLine("public final class $cls implements androidx.viewbinding.ViewBinding {")
            appendLine("    private final $rootType rootView;")
            rootField?.let { appendLine("    public final $rootType $it;") }
            fields.forEach { appendLine("    public final ${it.type} ${it.name};") }
            appendLine()
            val params = listOf("$rootType rootView") + fields.map { "${it.type} ${it.name}" }
            appendLine("    private $cls(${params.joinToString(", ")}) {")
            appendLine("        this.rootView = rootView;")
            rootField?.let { appendLine("        this.$it = rootView;") }
            fields.forEach { appendLine("        this.${it.name} = ${it.name};") }
            appendLine("    }")
            appendLine()
            appendLine("    @Override")
            appendLine("    public $rootType getRoot() {")
            appendLine("        return rootView;")
            appendLine("    }")
            appendLine()
            if (isMerge) {
                appendLine("    public static $cls inflate(android.view.LayoutInflater inflater, android.view.ViewGroup parent) {")
                appendLine("        if (parent == null) throw new NullPointerException(\"parent\");")
                appendLine("        inflater.inflate($namespace.R.layout.$layout, parent);")
                appendLine("        return bind(parent);")
                appendLine("    }")
            } else {
                appendLine("    public static $cls inflate(android.view.LayoutInflater inflater) {")
                appendLine("        return inflate(inflater, null, false);")
                appendLine("    }")
                appendLine()
                appendLine("    public static $cls inflate(android.view.LayoutInflater inflater, android.view.ViewGroup parent, boolean attachToParent) {")
                appendLine("        android.view.View root = inflater.inflate($namespace.R.layout.$layout, parent, false);")
                appendLine("        if (attachToParent) parent.addView(root);")
                appendLine("        return bind(root);")
                appendLine("    }")
            }
            appendLine()
            appendLine("    public static $cls bind(android.view.View rootView) {")
            fields.forEach { f ->
                if (f.include != null) {
                    appendLine("        android.view.View ${f.name}View = rootView.findViewById($namespace.R.id.${f.id});")
                    if (!f.nullable) appendLine("        if (${f.name}View == null) throw missing(rootView, $namespace.R.id.${f.id});")
                    appendLine("        ${f.type} ${f.name} = ${f.name}View == null ? null : ${f.type}.bind(${f.name}View);")
                } else {
                    appendLine("        ${f.type} ${f.name} = (${f.type}) rootView.findViewById($namespace.R.id.${f.id});")
                    if (!f.nullable) appendLine("        if (${f.name} == null) throw missing(rootView, $namespace.R.id.${f.id});")
                }
            }
            val args = listOf("($rootType) rootView") + fields.map { it.name }
            appendLine("        return new $cls(${args.joinToString(", ")});")
            appendLine("    }")
            appendLine()
            appendLine("    private static NullPointerException missing(android.view.View rootView, int id) {")
            appendLine("        return new NullPointerException(\"Missing required view with ID: \" + rootView.getResources().getResourceName(id));")
            appendLine("    }")
            appendLine("}")
        }
    }
}
