package com.bori.app.data.build

import android.content.Context
import android.util.Log
import com.bori.app.data.project.ProjectStateHolder
import com.bori.core.build.LiveCompileEngine
import com.bori.core.build.tools.ToolsManager
import com.bori.core.editor.EditorController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(kotlinx.coroutines.FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Singleton
class LiveDiagnosticsController @Inject constructor(
    private val editorController: EditorController,
    private val projectStateHolder: ProjectStateHolder,
    private val diagnosticsStateHolder: DiagnosticsStateHolder,
    private val buildStateHolder: BuildStateHolder,
    private val liveCompileEngine: LiveCompileEngine,
    private val toolsManager: ToolsManager,
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun inferSourceRoot(filePath: String?): File? {
        var dir = filePath?.let { File(it).parentFile } ?: return null
        while (dir.parentFile != null) {
            if (dir.name == "java" &&
                dir.parentFile?.name == "main" &&
                dir.parentFile?.parentFile?.name == "src") return dir
            dir = dir.parentFile ?: break
        }
        return null
    }

    fun start() {
        scope.launch {
            editorController.currentState
                .filterNotNull()
                .filter { it.language == "java" || it.language == "kotlin" }
                .debounce(3_000L)
                .distinctUntilChangedBy { it.content }
                .flatMapLatest { state ->
                    flow {
                        Log.d("LiveDiag", "debounce fired (${state.language})")
                        if (buildStateHolder.lastBuild.value.status == "building") {
                            Log.d("LiveDiag", "skip: build in progress")
                            return@flow
                        }
                        val project = projectStateHolder.activeProject.value
                        val sourceRoot: File
                        val rJavaDir: File?
                        if (project != null) {
                            sourceRoot = File(project.rootPath, "app/src/main/java")
                            rJavaDir = File(context.filesDir, "builds/${project.name}/r/java")
                                .takeIf { it.isDirectory }
                        } else {
                            sourceRoot = inferSourceRoot(state.filePath) ?: run {
                                Log.d("LiveDiag", "skip: cannot infer source root from ${state.filePath}")
                                return@flow
                            }
                            rJavaDir = null
                        }
                        if (!sourceRoot.isDirectory) {
                            Log.d("LiveDiag", "skip: sourceRoot missing at ${sourceRoot.path}")
                            return@flow
                        }

                        val extraClasspath = buildList {
                            val androidxJar = toolsManager.androidxClassesJar
                            if (androidxJar.exists()) add(androidxJar)
                            // Include user-resolved libs from the most recent build if available
                            if (project != null) {
                                val userLibsJar = File(context.filesDir, "builds/${project.name}/user-libs/user-libs.jar")
                                if (userLibsJar.exists()) add(userLibsJar)
                            }
                        }

                        Log.d("LiveDiag", "starting live compile: ${sourceRoot.path}, classpath: ${extraClasspath.size} jar(s)")
                        emit(liveCompileEngine.compile(sourceRoot, rJavaDir, extraClasspath))
                    }
                }
                .catch { }
                .collect { diagnostics ->
                    Log.d("LiveDiag", "diagnostics received: ${diagnostics.size}")
                    diagnosticsStateHolder.update(diagnostics)
                }
        }
    }
}
