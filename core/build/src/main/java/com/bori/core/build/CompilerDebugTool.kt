package com.bori.core.build

import android.util.Log
import com.bori.core.build.tools.ToolsManager
import dalvik.system.PathClassLoader
import java.io.File
import javax.inject.Inject

class CompilerDebugTool @Inject constructor(private val tools: ToolsManager) {

    fun debugResourceLookup() {
        val loaderPath = "${tools.kotlincJar.absolutePath}:" +
                "${tools.kotlinStdlibDexJar.absolutePath}:" +
                "${tools.kotlinReflectDexJar.absolutePath}:" +
                "${tools.trove4jDexJar.absolutePath}"
        
        Log.d(TAG, "Testing resource lookup with loaderPath: $loaderPath")
        
        val loader = PathClassLoader(loaderPath, this.javaClass.classLoader)
        
        val className = "org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment\$Companion"
        try {
            val clazz = loader.loadClass(className)
            Log.d(TAG, "Successfully loaded class: $className")
            
            val resourcePath = "/" + className.replace('.', '/').replace('$', '\$') + ".class"
            val resource = clazz.getResource(resourcePath)
            
            if (resource != null) {
                Log.d(TAG, "Resource FOUND: $resourcePath -> $resource")
            } else {
                Log.e(TAG, "Resource NOT FOUND: $resourcePath")
                
                // Try without leading slash
                val resourcePathNoSlash = className.replace('.', '/') + ".class"
                val resource2 = loader.getResource(resourcePathNoSlash)
                if (resource2 != null) {
                    Log.d(TAG, "Resource FOUND (no slash): $resourcePathNoSlash -> $resource2")
                } else {
                    Log.e(TAG, "Resource NOT FOUND (no slash): $resourcePathNoSlash")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load class $className", e)
        }
    }

    companion object {
        private const val TAG = "CompilerDebug"
    }
}
