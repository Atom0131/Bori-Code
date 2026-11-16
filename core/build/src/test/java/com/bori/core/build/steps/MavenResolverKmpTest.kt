package com.bori.core.build.steps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MavenResolverKmpTest {
    private fun variant(name: String, usage: String, platform: String, module: String) = """
        {"name":"$name","attributes":{"org.gradle.usage":"$usage","org.jetbrains.kotlin.platform.type":"$platform"},
         "available-at":{"url":"../../$module/0.6.0/$module-0.6.0.module","group":"org.jetbrains.kotlinx","module":"$module","version":"0.6.0"}}
    """

    @Test
    fun multiplatformRootPointsAtItsJvmJar() {
        // Shaped like kotlinx-datetime 0.6.0's .module: no Android variant, a JVM one.
        val json = """{"formatVersion":"1.1","variants":[
            ${variant("jsApiElements-published", "kotlin-api", "js", "kotlinx-datetime-js")},
            ${variant("jvmApiElements-published", "java-api", "jvm", "kotlinx-datetime-jvm")},
            ${variant("jvmRuntimeElements-published", "java-runtime", "jvm", "kotlinx-datetime-jvm")}
        ]}"""
        assertEquals("org.jetbrains.kotlinx:kotlinx-datetime-jvm:0.6.0", MavenResolver.kmpTarget(json))
    }

    @Test
    fun androidReleaseVariantWinsOverJvm() {
        val json = """{"variants":[
            ${variant("jvmRuntimeElements-published", "java-runtime", "jvm", "lib-jvm")},
            ${variant("androidDebugRuntimeElements-published", "java-runtime", "androidJvm", "lib-android-debug")},
            ${variant("androidReleaseRuntimeElements-published", "java-runtime", "androidJvm", "lib-android")}
        ]}"""
        assertEquals("org.jetbrains.kotlinx:lib-android:0.6.0", MavenResolver.kmpTarget(json))
    }

    @Test
    fun plainLibraryHasNoTarget() {
        // A normal library's variants carry files, not available-at.
        val json = """{"variants":[{"name":"releaseRuntimeElements","attributes":{"org.gradle.usage":"java-runtime"},"files":[{"name":"x.aar"}]}]}"""
        assertNull(MavenResolver.kmpTarget(json))
        assertNull(MavenResolver.kmpTarget("not json"))
    }
}
