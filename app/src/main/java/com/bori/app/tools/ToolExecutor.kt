package com.bori.app.tools

import android.content.Context
import android.content.Intent
import com.bori.app.ui.build.canInstallPackages
import com.bori.app.ui.build.launchApkInstaller
import com.bori.app.ui.build.openInstallPermissionSettings
import com.bori.app.data.build.BuildStateHolder
import com.bori.app.data.build.DiagnosticsStateHolder
import com.bori.app.data.prefs.UserPreferencesRepository
import com.bori.app.data.project.ProjectStateHolder
import com.bori.app.service.ChatForegroundService
import com.bori.core.build.BuildEngine
import com.bori.core.build.model.BuildEvent
import com.bori.core.build.model.BuildVariant
import com.bori.core.editor.EditorController
import com.bori.core.security.KeyVault
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class ToolExecutionResult(val output: String, val isError: Boolean = false)

/**
 * Executes tool calls requested by the AI agent.
 *
 * All file-system operations are sandboxed to [SANDBOX_ROOT] to prevent the AI
 * from reading or writing outside the user's Bori Code project directory.
 */
@Singleton
class ToolExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val editorController: EditorController,
    private val buildEngine: BuildEngine,
    private val projectStateHolder: ProjectStateHolder,
    private val buildStateHolder: BuildStateHolder,
    private val diagnosticsStateHolder: DiagnosticsStateHolder,
    private val projectRepository: com.bori.core.project.ProjectRepository,
    private val prefsRepository: UserPreferencesRepository,
    private val keyVault: KeyVault,
    private val buildLock: com.bori.app.data.build.BuildLock,
) {

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    suspend fun execute(name: String, inputJson: String): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            try {
                val args = JSONObject(inputJson)
                when (name) {
                    "read_file"          -> readFile(args)
                    "read_files"         -> readFiles(args)
                    "write_file"         -> writeFile(args)
                    "list_directory"     -> listDirectory(args)
                    "create_directory"   -> createDirectory(args)
                    "run_shell"          -> runShell(args)
                    "get_editor_context"      -> getEditorContext()
                    "apply_edit"              -> applyEdit(args)
                    "get_build_logs"          -> getBuildLogs()
                    "trigger_build"           -> triggerBuild()
                    "install_apk"             -> installApk()
                    "launch_app"              -> launchApp(args)
                    "get_diagnostics"         -> getDiagnostics()
                    "open_file"               -> openFile(args)
                    "get_project_info"        -> getProjectInfo()
                    "add_dependency"          -> addDependency(args)
                    "add_manifest_permission" -> addManifestPermission(args)
                    "write_files"             -> writeFiles(args)
                    "web_search"              -> webSearch(args)
                    "web_fetch"               -> webFetch(args)
                    "download_image"          -> downloadImage(args)
                    "write_plan"              -> writePlan(args)
                    // HITL tools below never actually run here — the agent loop (ChatViewModel)
                    // intercepts the ToolUse before dispatch and drives the UI flow. These stubs
                    // are isError=true fallbacks in case one ever reaches the executor.
                    "ask_user"                -> ToolExecutionResult("ask_user: intercepted by agent loop.", isError = true)
                    "ask_questions"           -> ToolExecutionResult("ask_questions: intercepted by agent loop.", isError = true)
                    "suggest_plan_mode"       -> ToolExecutionResult("suggest_plan_mode: intercepted by agent loop.", isError = true)
                    "exit_plan_mode"          -> ToolExecutionResult("exit_plan_mode: intercepted by agent loop.", isError = true)
                    "compact_conversation"    -> ToolExecutionResult("compact_conversation: intercepted by agent loop.", isError = true)
                    else -> ToolExecutionResult("Unknown tool: $name", isError = true)
                }
            } catch (e: Exception) {
                ToolExecutionResult("Tool error: ${e.message}", isError = true)
            }
        }

    // -------------------------------------------------------------------------
    // Tool implementations
    // -------------------------------------------------------------------------

    private fun readFile(args: JSONObject): ToolExecutionResult {
        val path = args.getString("path")
        val file = guardedFile(path) ?: return sandboxError(path)
        if (!file.exists()) return ToolExecutionResult("File not found: $path", isError = true)
        if (!file.isFile) return ToolExecutionResult("Not a file: $path", isError = true)
        val content = file.readText()
        // Cap at ~100 KB to avoid flooding the context window
        return if (content.length > MAX_READ_CHARS) {
            ToolExecutionResult(content.take(MAX_READ_CHARS) + "\n... [truncated at $MAX_READ_CHARS chars]")
        } else {
            ToolExecutionResult(content)
        }
    }

    private fun readFiles(args: JSONObject): ToolExecutionResult {
        val paths = args.getJSONArray("paths")
        val sb = StringBuilder()
        for (i in 0 until paths.length()) {
            val path = paths.getString(i)
            if (i > 0) sb.append("\n\n")
            sb.append("=== $path ===\n")
            val file = guardedFile(path)
            when {
                file == null   -> sb.append("[ERROR: path outside sandbox]")
                !file.exists() -> sb.append("[ERROR: file not found]")
                !file.isFile   -> sb.append("[ERROR: not a file]")
                else -> {
                    val content = file.readText()
                    if (content.length > MAX_READ_CHARS) {
                        sb.append(content.take(MAX_READ_CHARS))
                        sb.append("\n... [truncated at $MAX_READ_CHARS chars]")
                    } else {
                        sb.append(content)
                    }
                }
            }
        }
        return ToolExecutionResult(sb.toString())
    }

    private suspend fun writeFile(args: JSONObject): ToolExecutionResult {
        val path = args.getString("path")
        val content = args.getString("content")
        val file = guardedFile(path) ?: return sandboxError(path)
        file.parentFile?.mkdirs()
        file.writeText(content)
        detectAndRegisterProject(file.parentFile)
        return ToolExecutionResult("Written ${content.length} chars to $path")
    }

    private suspend fun detectAndRegisterProject(startDir: File?) {
        val sandbox = File(SANDBOX_ROOT).canonicalPath
        var dir = startDir
        while (dir != null && dir.canonicalPath.startsWith(sandbox)) {
            if (File(dir, "settings.gradle").exists() || File(dir, "settings.gradle.kts").exists()) {
                val project = projectRepository.upsertFromPath(dir.absolutePath)
                if (projectStateHolder.activeProject.value?.id != project.id) {
                    projectStateHolder.setActiveProject(project)
                }
                return
            }
            dir = dir.parentFile
        }
    }

    private fun listDirectory(args: JSONObject): ToolExecutionResult {
        val path = args.getString("path")
        val dir = guardedFile(path) ?: return sandboxError(path)
        if (!dir.exists()) return ToolExecutionResult("Directory not found: $path", isError = true)
        if (!dir.isDirectory) return ToolExecutionResult("Not a directory: $path", isError = true)
        val listing = buildString {
            appendLine(path)
            appendTree(dir, indent = "  ", depth = 0, maxDepth = 3)
        }
        return ToolExecutionResult(listing.trimEnd())
    }

    private fun createDirectory(args: JSONObject): ToolExecutionResult {
        val path = args.getString("path")
        val dir = guardedFile(path) ?: return sandboxError(path)
        if (dir.mkdirs() || dir.exists()) {
            return ToolExecutionResult("Directory created: $path")
        }
        return ToolExecutionResult("Failed to create directory: $path", isError = true)
    }

    private fun runShell(args: JSONObject): ToolExecutionResult {
        val command = args.getString("command")
        val process = ProcessBuilder("sh", "-c", command)
            .directory(File(SANDBOX_ROOT))
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().use { it.readText() }
        val finished = process.waitFor(SHELL_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return ToolExecutionResult("Command timed out after ${SHELL_TIMEOUT_SECONDS}s", isError = true)
        }
        val exitCode = process.exitValue()
        val result = output.trim().ifEmpty { "(no output)" }
        return if (exitCode == 0) {
            ToolExecutionResult(result)
        } else {
            ToolExecutionResult("Exit code $exitCode:\n$result", isError = true)
        }
    }

    // -------------------------------------------------------------------------
    // Editor + build tool handlers
    // -------------------------------------------------------------------------

    private fun getEditorContext(): ToolExecutionResult {
        val tabs = editorController.tabState.value.tabs
        val active = editorController.tabState.value.activeTab
        val state = editorController.currentState.value
        val sb = StringBuilder()
        sb.appendLine("Active file: ${active?.path ?: "(none)"}")
        sb.appendLine("Cursor offset: ${state?.cursorOffset ?: -1}")
        if (!state?.selectedText.isNullOrEmpty()) {
            sb.appendLine("Selected text: ${state!!.selectedText}")
        }
        // Diagnostics for the active file
        val activePath = active?.path
        if (activePath != null) {
            val fileDiags = diagnosticsStateHolder.diagnostics.value.filter { it.filePath == activePath }
            if (fileDiags.isNotEmpty()) {
                sb.appendLine("Diagnostics in active file (${fileDiags.size}):")
                fileDiags.forEach { d ->
                    sb.appendLine("  [${d.severity.name}] line ${d.line}: ${d.message}")
                }
            }
        }
        sb.appendLine("Open tabs (${tabs.size}):")
        tabs.forEach { tab ->
            sb.appendLine("  ${tab.path}${if (tab.isDirty) " [unsaved]" else ""}")
        }
        return ToolExecutionResult(sb.toString().trimEnd())
    }

    private fun getDiagnostics(): ToolExecutionResult {
        val diags = diagnosticsStateHolder.diagnostics.value
        if (diags.isEmpty()) {
            return ToolExecutionResult("No diagnostics. Run a build first.")
        }
        val sb = StringBuilder()
        sb.appendLine("Diagnostics (${diags.size} total):")
        diags.groupBy { it.filePath }.forEach { (path, items) ->
            sb.appendLine("  $path")
            items.forEach { d ->
                val loc = if (d.column > 0) "${d.line}:${d.column}" else "${d.line}"
                sb.appendLine("    [${d.severity.name}] $loc — ${d.message}")
            }
        }
        return ToolExecutionResult(sb.toString().trimEnd())
    }

    private suspend fun applyEdit(args: JSONObject): ToolExecutionResult {
        val path = args.getString("file_path")
        val oldText = args.getString("old_text")
        val newText = args.getString("new_text")
        guardedFile(path) ?: return sandboxError(path)
        return editorController.applyFileEdit(path, oldText, newText).fold(
            onSuccess = { msg -> ToolExecutionResult(msg) },
            onFailure = { e  -> ToolExecutionResult(e.message ?: "Edit failed", isError = true) },
        )
    }

    private fun getBuildLogs(): ToolExecutionResult {
        val b = buildStateHolder.lastBuild.value
        val sb = StringBuilder()
        sb.appendLine("Status: ${b.status}")
        b.apkPath?.let { sb.appendLine("APK: $it") }
        b.error?.let  { sb.appendLine("Error: $it") }
        if (b.logs.isNotEmpty()) {
            sb.appendLine("--- Log (${b.logs.size} lines) ---")
            b.logs.forEach { sb.appendLine(it) }
        }
        return ToolExecutionResult(sb.toString().trimEnd())
    }

    private suspend fun triggerBuild(): ToolExecutionResult {
        val project = projectStateHolder.activeProject.value
            ?: return ToolExecutionResult(
                "No active project — open a project first.", isError = true
            )

        buildStateHolder.onStarted()
        val prefs = prefsRepository.preferences.first()
        val timeoutMinutes = prefs.kotlinTimeoutMinutes
        val compilerProfile = prefs.compilerPerformanceProfile
        val allLogs = mutableListOf<String>()
        var status = "failed"
        var apkPath: String? = null
        var errorMsg: String? = null

        ChatForegroundService.acquire(context, ChatForegroundService.Reason.BUILD, project.name)
        try {
            buildLock.withLock(onWait = { buildStateHolder.onLog(com.bori.app.data.build.BuildLock.WAITING) }) {
                buildEngine.build(
                    project = project,
                    kotlinTimeoutSeconds = timeoutMinutes * 60L,
                    compilerProfile = compilerProfile,
                    keystoreConfig = null,
                    variant = BuildVariant.DEBUG,
                ).collect { event ->
                    when (event) {
                        is BuildEvent.Log     -> { allLogs += event.message; buildStateHolder.onLog(event.message) }
                        is BuildEvent.Success -> { status = "success"; apkPath = event.apkPath; buildStateHolder.onSuccess(event.apkPath, event.applicationId) }
                        is BuildEvent.Failure -> { errorMsg = event.error; buildStateHolder.onFailure(event.error) }
                        BuildEvent.Started    -> Unit
                    }
                }
            }
        } finally {
            ChatForegroundService.release(context, ChatForegroundService.Reason.BUILD)
        }

        if (project.rootPath.isNotBlank()) {
            val summary = if (status == "success") "debug build (agent), APK ${apkPath?.let { File(it).name }}"
                else "debug build (agent), error: ${errorMsg?.lineSequence()?.firstOrNull().orEmpty().take(300)}"
            withContext(Dispatchers.IO) {
                runCatching { com.bori.core.project.agent.AgentFiles.appendBuildResult(File(project.rootPath), status == "success", summary) }
            }
        }

        val tail = allLogs.takeLast(20)
        val sb = StringBuilder()
        sb.appendLine("Build $status.")
        apkPath?.let { sb.appendLine("APK: $it") }
        errorMsg?.let { sb.appendLine("Error: $it") }
        sb.appendLine("--- Last ${tail.size} log lines ---")
        tail.forEach { sb.appendLine(it) }
        return ToolExecutionResult(sb.toString().trimEnd(), isError = status == "failed")
    }

    private fun installApk(): ToolExecutionResult {
        val apkPath = buildStateHolder.lastBuild.value.apkPath
            ?: return ToolExecutionResult("No APK available — run a build first.", isError = true)
        val apkFile = File(apkPath)
        if (!apkFile.exists()) return ToolExecutionResult("APK file not found: $apkPath", isError = true)
        if (!canInstallPackages(context)) {
            openInstallPermissionSettings(context)
            return ToolExecutionResult(
                "Opened 'Install unknown apps' settings — please grant the permission and ask me to install again.",
            )
        }
        launchApkInstaller(context, apkFile)
        return ToolExecutionResult("System installer launched for ${apkFile.name}. The user must tap Install to confirm.")
    }

    private fun launchApp(args: JSONObject): ToolExecutionResult {
        val packageName = args.optString("package_name").ifBlank {
            buildStateHolder.lastBuild.value.applicationId
                ?: projectStateHolder.activeProject.value?.packageName
                ?: return ToolExecutionResult("No active project and no package_name provided.", isError = true)
        }
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return ToolExecutionResult("'$packageName' is not installed or has no launcher activity.", isError = true)
        context.startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return ToolExecutionResult("Launched $packageName.")
    }

    private fun getProjectInfo(): ToolExecutionResult {
        val project = projectStateHolder.activeProject.value
            ?: return ToolExecutionResult("No active project — open a project first.", isError = true)
        val root = File(project.rootPath)
        val sb = StringBuilder()

        sb.appendLine("Project:   ${project.name}")
        sb.appendLine("Package:   ${project.packageName}")
        sb.appendLine("Root:      ${project.rootPath}")
        sb.appendLine("minSdk:    ${project.minSdk}")
        val lang = if (project.template.name.endsWith("_KOTLIN")) "Kotlin" else "Java"
        sb.appendLine("Language:  $lang")

        // Dependencies from build.gradle(.kts)
        val gradleFile = listOf(File(root, "app/build.gradle.kts"), File(root, "app/build.gradle"))
            .firstOrNull { it.exists() }
        if (gradleFile != null) {
            val text = gradleFile.readText()
            val depsBlock = extractDependenciesBlock(text)
            if (depsBlock != null) {
                sb.appendLine("\nDependencies (${gradleFile.name}):")
                depsBlock.lines()
                    .map { it.trim() }
                    .filter { it.isNotBlank() && !it.startsWith("//") }
                    .forEach { sb.appendLine("  $it") }
            } else {
                sb.appendLine("\nDependencies: (none declared)")
            }
        } else {
            sb.appendLine("\nbuild.gradle not found")
        }

        // AndroidManifest.xml
        val manifestFile = listOf(
            File(root, "app/src/main/AndroidManifest.xml"),
            File(root, "app/AndroidManifest.xml"),
        ).firstOrNull { it.exists() }
        if (manifestFile != null) {
            val text = manifestFile.readText()
            val permissions = Regex("""<uses-permission[^/]*/?>""").findAll(text)
                .map { it.value.trim() }.toList()
            if (permissions.isNotEmpty()) {
                sb.appendLine("\nPermissions:")
                permissions.forEach { sb.appendLine("  $it") }
            } else {
                sb.appendLine("\nPermissions: (none declared)")
            }
            val activities = Regex("""android:name\s*=\s*["']([^"']+)["']""").findAll(text)
                .map { it.groupValues[1] }.filter { !it.contains("permission", ignoreCase = true) }.toList()
            if (activities.isNotEmpty()) {
                sb.appendLine("\nComponents:")
                activities.forEach { sb.appendLine("  $it") }
            }
        } else {
            sb.appendLine("\nAndroidManifest.xml not found")
        }

        return ToolExecutionResult(sb.toString().trimEnd())
    }

    private suspend fun addDependency(args: JSONObject): ToolExecutionResult {
        val configuration = args.getString("configuration")
        val coordinate = args.getString("coordinate")
        val project = projectStateHolder.activeProject.value
            ?: return ToolExecutionResult("No active project.", isError = true)
        val root = File(project.rootPath)
        val gradleFile = listOf(File(root, "app/build.gradle.kts"), File(root, "app/build.gradle"))
            .firstOrNull { it.exists() }
            ?: return ToolExecutionResult("build.gradle not found in ${root.absolutePath}", isError = true)

        val original = withContext(Dispatchers.IO) { gradleFile.readText() }
        if (original.contains(coordinate)) {
            return ToolExecutionResult("$coordinate is already declared in ${gradleFile.name} — nothing changed.")
        }

        val newLine = "    $configuration(\"$coordinate\")"
        val updated = insertIntoDependenciesBlock(original, newLine)
        editorController.applyFileEdit(gradleFile.absolutePath, "", updated)
        return ToolExecutionResult("Added to ${gradleFile.name}:\n  $newLine")
    }

    private suspend fun addManifestPermission(args: JSONObject): ToolExecutionResult {
        val permission = args.getString("permission")
        val project = projectStateHolder.activeProject.value
            ?: return ToolExecutionResult("No active project.", isError = true)
        val root = File(project.rootPath)
        val manifestFile = listOf(
            File(root, "app/src/main/AndroidManifest.xml"),
            File(root, "app/AndroidManifest.xml"),
        ).firstOrNull { it.exists() }
            ?: return ToolExecutionResult("AndroidManifest.xml not found.", isError = true)

        val original = withContext(Dispatchers.IO) { manifestFile.readText() }
        if (original.contains(permission)) {
            return ToolExecutionResult("$permission is already declared — nothing changed.")
        }

        val permLine = "    <uses-permission android:name=\"$permission\" />"
        val updated = if (original.contains("<application")) {
            original.replaceFirst("<application", "$permLine\n\n    <application")
        } else {
            original.replaceFirst("</manifest>", "$permLine\n</manifest>")
        }
        editorController.applyFileEdit(manifestFile.absolutePath, "", updated)
        return ToolExecutionResult("Added to AndroidManifest.xml:\n  $permLine")
    }

    private suspend fun writeFiles(args: JSONObject): ToolExecutionResult {
        val filesArray = args.getJSONArray("files")

        val prefs = prefsRepository.preferences.first()
        val (limitBytes, tierName) = when {
            prefs.aiWorkTurns <= 50  -> 512 * 1024L to "Low"
            prefs.aiWorkTurns <= 100 -> 2 * 1024 * 1024L to "Medium"
            else                     -> 8 * 1024 * 1024L to "High"
        }
        val totalBytes = (0 until filesArray.length()).sumOf {
            filesArray.getJSONObject(it).getString("content").toByteArray().size.toLong()
        }
        if (totalBytes > limitBytes) {
            val limitKb = limitBytes / 1024
            val totalKb = totalBytes / 1024
            return ToolExecutionResult(
                "write_files batch is too large: ${totalKb}KB requested, ${limitKb}KB max in $tierName mode. " +
                "Split into smaller batches or switch to a higher performance mode in Settings.",
                isError = true,
            )
        }

        val written = mutableListOf<String>()
        val failed = mutableListOf<String>()

        for (i in 0 until filesArray.length()) {
            val obj = filesArray.getJSONObject(i)
            val path = obj.getString("path")
            val content = obj.getString("content")
            val file = guardedFile(path)
            if (file == null) {
                failed += "✗ $path — outside sandbox"
                continue
            }
            withContext(Dispatchers.IO) {
                file.parentFile?.mkdirs()
                file.writeText(content)
            }
            detectAndRegisterProject(file.parentFile)
            written += "✓ $path"
        }

        val sb = StringBuilder()
        if (written.isNotEmpty()) { sb.appendLine("Written (${written.size}):"); written.forEach { sb.appendLine("  $it") } }
        if (failed.isNotEmpty()) { sb.appendLine("Failed  (${failed.size}):"); failed.forEach { sb.appendLine("  $it") } }
        return ToolExecutionResult(sb.toString().trimEnd(), isError = written.isEmpty() && failed.isNotEmpty())
    }

    private suspend fun openFile(args: JSONObject): ToolExecutionResult {
        val path = args.getString("path")
        val line = if (args.has("line")) args.getInt("line") else -1
        guardedFile(path) ?: return sandboxError(path)
        val file = File(path)
        if (!file.exists()) return ToolExecutionResult("File not found: $path", isError = true)
        val content = withContext(Dispatchers.IO) { file.readText() }
        withContext(Dispatchers.Main) {
            editorController.openFile(context, path, content)
            if (line > 0) editorController.jumpToLine(path, line)
        }
        return ToolExecutionResult("Opened $path${if (line > 0) " at line $line" else ""}")
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Returns a [File] only if [path] resolves inside [SANDBOX_ROOT], or null otherwise.
     * Resolves symlinks via [canonicalPath] to prevent directory-traversal attacks.
     */
    private fun guardedFile(path: String): File? {
        val requested = File(path).canonicalPath
        val sandbox = File(SANDBOX_ROOT).canonicalPath
        return if (requested.startsWith(sandbox)) File(requested) else null
    }

    private fun sandboxError(path: String) =
        ToolExecutionResult(
            "Access denied: '$path' is outside the Bori Code sandbox ($SANDBOX_ROOT).",
            isError = true,
        )

    private fun StringBuilder.appendTree(dir: File, indent: String, depth: Int, maxDepth: Int) {
        if (depth >= maxDepth) {
            appendLine("$indent...")
            return
        }
        val entries = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
            ?: return
        entries.forEach { entry ->
            if (entry.isDirectory) {
                appendLine("$indent${entry.name}/")
                appendTree(entry, "$indent  ", depth + 1, maxDepth)
            } else {
                appendLine("$indent${entry.name}")
            }
        }
    }

    /** Returns the content between the braces of the first `dependencies { }` block, or null. */
    private fun extractDependenciesBlock(gradle: String): String? {
        val start = gradle.indexOf("dependencies {").takeIf { it >= 0 } ?: return null
        var depth = 0
        var i = start
        var blockStart = -1
        while (i < gradle.length) {
            when (gradle[i]) {
                '{' -> { if (depth == 0) blockStart = i + 1; depth++ }
                '}' -> { depth--; if (depth == 0) return gradle.substring(blockStart, i) }
            }
            i++
        }
        return null
    }

    /** Inserts [newLine] before the closing `}` of the `dependencies { }` block. */
    private fun insertIntoDependenciesBlock(gradle: String, newLine: String): String {
        val blockIdx = gradle.indexOf("dependencies {").takeIf { it >= 0 }
            ?: return "$gradle\ndependencies {\n$newLine\n}\n"
        var depth = 0
        var i = blockIdx
        while (i < gradle.length) {
            when (gradle[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return gradle.substring(0, i) + newLine + "\n" + gradle.substring(i) }
            }
            i++
        }
        return "$gradle\ndependencies {\n$newLine\n}\n"
    }

    private fun writePlan(args: JSONObject): ToolExecutionResult {
        val summary = args.optString("summary", "").trim()
        val plan    = args.optString("plan", "").trim()
        if (plan.isEmpty()) return ToolExecutionResult("write_plan: plan is empty.", isError = true)
        return ToolExecutionResult("PLAN_READY\n$summary\n---\n$plan")
    }

    // -------------------------------------------------------------------------
    // Internet tools
    // -------------------------------------------------------------------------

    private fun webSearch(args: JSONObject): ToolExecutionResult {
        val query = args.optString("query").trim()
        if (query.isEmpty()) return ToolExecutionResult("web_search: query is empty.", isError = true)
        val apiKey = keyVault.retrieveServiceKey(KeyVault.TAVILY)
            ?: return ToolExecutionResult(
                "web_search is not configured. Add a Tavily API key in Settings → Web Search to enable web research.",
                isError = true,
            )
        val maxResults = args.optInt("max_results", 5).coerceIn(1, 10)
        val body = JSONObject().apply {
            put("api_key", apiKey)
            put("query", query)
            put("search_depth", "basic")
            put("include_answer", true)
            put("max_results", maxResults)
        }.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://api.tavily.com/search")
            .post(body)
            .build()
        return try {
            httpClient.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return ToolExecutionResult("web_search failed: HTTP ${resp.code} ${text.take(300)}", isError = true)
                }
                val json = JSONObject(text)
                val sb = StringBuilder()
                val answer = json.optString("answer").trim()
                if (answer.isNotEmpty()) { sb.appendLine("Answer: $answer"); sb.appendLine() }
                val results = json.optJSONArray("results") ?: JSONArray()
                if (results.length() == 0 && answer.isEmpty()) {
                    return ToolExecutionResult("No results for \"$query\".")
                }
                sb.appendLine("Results (${results.length()}):")
                for (i in 0 until results.length()) {
                    val r = results.getJSONObject(i)
                    sb.appendLine("${i + 1}. ${r.optString("title").ifBlank { "(untitled)" }}")
                    sb.appendLine("   ${r.optString("url")}")
                    val content = r.optString("content").trim()
                    if (content.isNotEmpty()) sb.appendLine("   ${content.take(500)}")
                }
                ToolExecutionResult(sb.toString().trimEnd())
            }
        } catch (e: Exception) {
            ToolExecutionResult("web_search error: ${e.message}", isError = true)
        }
    }

    private fun webFetch(args: JSONObject): ToolExecutionResult {
        val url = args.optString("url").trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ToolExecutionResult("web_fetch: url must be an http(s) URL.", isError = true)
        }
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        return try {
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return ToolExecutionResult("web_fetch failed: HTTP ${resp.code} for $url", isError = true)
                }
                val contentType = resp.body?.contentType()?.toString().orEmpty()
                val raw = resp.body?.string().orEmpty()
                val text = if (contentType.contains("html", ignoreCase = true)) htmlToText(raw) else raw
                val capped = if (text.length > MAX_READ_CHARS) {
                    text.take(MAX_READ_CHARS) + "\n... [truncated at $MAX_READ_CHARS chars]"
                } else text
                ToolExecutionResult("URL: $url\n\n$capped")
            }
        } catch (e: Exception) {
            ToolExecutionResult("web_fetch error: ${e.message}", isError = true)
        }
    }

    private fun downloadImage(args: JSONObject): ToolExecutionResult {
        val url = args.optString("url").trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ToolExecutionResult("download_image: url must be an http(s) URL.", isError = true)
        }
        val rawName = args.optString("file_name").trim()
        if (rawName.isEmpty()) return ToolExecutionResult("download_image: file_name is required.", isError = true)

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        return try {
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return ToolExecutionResult("download_image failed: HTTP ${resp.code} for $url", isError = true)
                }
                val contentType = resp.body?.contentType()?.toString().orEmpty()
                if (!contentType.startsWith("image/", ignoreCase = true)) {
                    return ToolExecutionResult(
                        "download_image: URL is not an image (content-type: ${contentType.ifEmpty { "unknown" }}).",
                        isError = true,
                    )
                }
                val bytes = resp.body?.bytes() ?: return ToolExecutionResult("download_image: empty response.", isError = true)
                if (bytes.size > MAX_IMAGE_BYTES) {
                    return ToolExecutionResult("download_image: image too large (${bytes.size} bytes, max $MAX_IMAGE_BYTES).", isError = true)
                }

                val explicitPath = args.optString("path").trim()
                val targetFile: File = if (explicitPath.isNotEmpty()) {
                    guardedFile(explicitPath) ?: return sandboxError(explicitPath)
                } else {
                    val project = projectStateHolder.activeProject.value
                        ?: return ToolExecutionResult(
                            "download_image: no active project. Open or create a project first, or pass an explicit 'path'.",
                            isError = true,
                        )
                    val ext = extensionFor(rawName, contentType)
                    var base = rawName.substringBeforeLast('.').lowercase().replace(Regex("[^a-z0-9_]"), "_")
                    if (base.isEmpty() || !base.first().isLetter()) base = "img_$base"
                    val drawableDir = File(project.rootPath, "app/src/main/res/drawable")
                    guardedFile(File(drawableDir, "$base.$ext").absolutePath) ?: return sandboxError(drawableDir.absolutePath)
                }
                targetFile.parentFile?.mkdirs()
                targetFile.writeBytes(bytes)
                ToolExecutionResult("Saved ${bytes.size} bytes to ${targetFile.absolutePath}")
            }
        } catch (e: Exception) {
            ToolExecutionResult("download_image error: ${e.message}", isError = true)
        }
    }

    /** Picks a file extension from the requested name, falling back to the response content-type. */
    private fun extensionFor(fileName: String, contentType: String): String {
        fileName.substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() }?.let { return it }
        val sub = contentType.substringAfter("image/", "").substringBefore(";").substringBefore("+").trim().lowercase()
        return when (sub) {
            "jpeg" -> "jpg"
            ""     -> "png"
            else   -> sub
        }
    }

    /** Crude HTML→text: drops script/style, converts block tags to newlines, strips remaining tags. */
    private fun htmlToText(html: String): String {
        var s = html
        s = s.replace(Regex("(?is)<(script|style|head|noscript)[^>]*>.*?</\\1>"), " ")
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
        s = s.replace(Regex("(?i)</(p|div|li|h[1-6]|tr|section|article)>"), "\n")
        s = s.replace(Regex("<[^>]+>"), " ")
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        s = s.replace(Regex("[ \\t]+"), " ")
        s = s.replace(Regex("\\n[ \\t]*\\n[ \\t]*\\n+"), "\n\n")
        return s.trim()
    }

    private val SANDBOX_ROOT: String
        get() = com.bori.core.project.ProjectRepository.PROJECTS_ROOT.absolutePath

    companion object {
        private const val MAX_READ_CHARS = 100_000
        private const val SHELL_TIMEOUT_SECONDS = 30L
        private const val MAX_IMAGE_BYTES = 15 * 1024 * 1024
        private const val USER_AGENT = "Bori-Code/1.0 (Android)"
    }
}
