package com.bori.core.build.steps

import android.util.Log
import com.bori.core.build.tools.ToolsManager
import dalvik.system.PathClassLoader
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Runtime-dexes a Kotlin compiler plugin jar (raw `.class` bytecode resolved from Maven) into a
 * DEX jar so its classes can be loaded on ART by the embedded compiler's DelegateLastClassLoader.
 *
 * On ART you cannot define classes from `.class` bytecode at runtime, so Kotlin's normal plugin
 * loader (a URLClassLoader over the raw plugin jar) cannot instantiate the plugin's
 * ComponentRegistrar. The fix: dex the plugin here, add the result to the compiler's own
 * classloader, and pass the *raw* jar to `-Xplugin` only so ServiceLoaderLite's file-based scan
 * can discover the registrar class names (which then resolve via the parent/compiler loader).
 *
 * Reuses the same on-device D8 invocation pattern as [DexStep].
 */
class PluginDexer(private val tools: ToolsManager) {

    /**
     * Dexes [pluginJar] to [outDexJar] (a jar containing classes.dex). Cached: if [outDexJar]
     * already exists and is non-empty, dexing is skipped. Returns [outDexJar].
     */
    fun dex(pluginJar: File, outDexJar: File, emit: (String) -> Unit): File {
        if (outDexJar.exists() && outDexJar.length() > 0L) {
            emit("Plugin DEX cached: ${outDexJar.name}")
            return outDexJar
        }
        outDexJar.parentFile?.mkdirs()
        emit("D8: dexing compiler plugin ${pluginJar.name} (one-time)")

        val loader = PathClassLoader(tools.d8Jar.absolutePath, ClassLoader.getSystemClassLoader())
        val mainMethod = loader.loadClass("com.android.tools.r8.D8")
            .getMethod("main", Array<String>::class.java)

        // --min-api 26 keeps desugaring minimal. android-stubs as --lib so framework refs resolve.
        // Kotlin-compiler internals referenced by the plugin are intentionally NOT supplied here:
        // D8 emits them as dangling refs to be resolved at runtime by kotlinc.jar (which is on the
        // compiler's classloader). If D8 fails on a missing type, that surfaces in [emit].
        val args = arrayOf(
            "--output", outDexJar.absolutePath,
            "--min-api", "26",
            "--lib", tools.androidStubs.absolutePath,
            pluginJar.absolutePath,
        )

        val executor = Executors.newSingleThreadExecutor()
        try {
            executor.submit<Unit> { mainMethod.invoke(null, args as Any) }
                .get(DEX_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            throw RuntimeException("D8 timed out dexing plugin ${pluginJar.name} after ${DEX_TIMEOUT_SECONDS}s")
        } catch (e: Throwable) {
            val root = (e as? java.util.concurrent.ExecutionException)?.cause ?: e
            Log.e(TAG, "Plugin dexing failed: ${root.message}", root)
            outDexJar.delete()
            throw RuntimeException("D8 failed dexing plugin ${pluginJar.name}: ${root.message}", root)
        } finally {
            executor.shutdownNow()
        }

        emit("D8: plugin DEX ready → ${outDexJar.name} (${outDexJar.length() / 1024} KB)")
        return outDexJar
    }

    companion object {
        private const val TAG = "PluginDexer"
        private const val DEX_TIMEOUT_SECONDS = 180L
    }
}
