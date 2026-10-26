import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.TryCatchBlockNode

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// SDK versions the bundled tools come from. Pinned (not "latest installed") so every
// machine produces the same assets; android {} below compiles against the same versions.
// The committed assets came from platform 35 + build-tools 37.0.0 (D8 9.2.4).
val pinnedCompileSdk = 35
val pinnedBuildTools = "37.0.0"

// Every file copyBuildTools writes into src/main/assets/.
val generatedAssetNames = listOf(
    "d8.jar", "android-stubs.jar", "core-lambda-stubs.jar",
    "kotlinc.jar", "kotlin-stdlib.jar", "kotlin-stdlib-dex.jar", "kotlin-reflect-dex.jar", "trove4j-dex.jar",
    "swing-stubs-dex.jar", "jetbrains-annotations-dex.jar", "kotlinx-coroutines-dex.jar",
    // Kotlin compiler plugins and KSP, pre-dexed (dex + the original entries, for META-INF/services)
    "compose-plugin.jar", "serialization-plugin.jar", "parcelize-plugin.jar",
    "ksp-plugin.jar", "ksp-api.jar", "ksp-deps-dex.jar", "java-compiler-api-dex.jar", "processor-stubs-dex.jar",
    "androidx-classes.jar", "androidx-classes2.dex", "androidx-res-compiled.zip",
    "androidx-packages.txt", "androidx-manifest-entries.xml", "androidx-versions.txt",
)

// Reads the Android SDK path from local.properties or environment.
fun findAndroidSdk(): String? {
    val localProps = File(rootDir, "local.properties")
    if (localProps.exists()) {
        localProps.readLines().forEach { line ->
            if (line.startsWith("sdk.dir=")) return line.removePrefix("sdk.dir=").trim()
        }
    }
    return System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
}

/** Extracts a ZIP/AAR file to destDir using pure Java (no Gradle zipTree needed). */
fun extractZipTo(zipFile: File, destDir: File) {
    ZipInputStream(zipFile.inputStream().buffered()).use { zin ->
        var entry = zin.nextEntry
        while (entry != null) {
            if (!entry.isDirectory) {
                val dest = File(destDir, entry.name)
                dest.parentFile?.mkdirs()
                dest.outputStream().use { zin.copyTo(it) }
            }
            zin.closeEntry()
            entry = zin.nextEntry
        }
    }
}

/**
 * Returns the ASM opcode(s) needed to push a zero/null default and return it for a given
 * JVM method descriptor. Used to generate safe catch-block returns when patching methods
 * that look up sun.misc.Unsafe.getAndAddInt.
 */
fun returnInstructions(desc: String): List<InsnNode> {
    val retType = desc.substringAfterLast(')')
    return when {
        retType == "V"                          -> listOf(InsnNode(Opcodes.POP), InsnNode(Opcodes.RETURN))
        retType in listOf("I","Z","B","S","C")  -> listOf(InsnNode(Opcodes.POP), InsnNode(Opcodes.ICONST_0), InsnNode(Opcodes.IRETURN))
        retType == "J"                          -> listOf(InsnNode(Opcodes.POP), InsnNode(Opcodes.LCONST_0), InsnNode(Opcodes.LRETURN))
        retType == "F"                          -> listOf(InsnNode(Opcodes.POP), InsnNode(Opcodes.FCONST_0), InsnNode(Opcodes.FRETURN))
        retType == "D"                          -> listOf(InsnNode(Opcodes.POP), InsnNode(Opcodes.DCONST_0), InsnNode(Opcodes.DRETURN))
        else                                    -> listOf(InsnNode(Opcodes.POP), InsnNode(Opcodes.ACONST_NULL), InsnNode(Opcodes.ARETURN))
    }
}

/**
 * For every method in the class that contains an LDC "getAndAddInt" constant (meaning it
 * reflectively looks up sun.misc.Unsafe.getAndAddInt), wraps the entire method body in
 * try-catch(Throwable) so a hidden-API NoSuchMethodException on Android 14/15 never
 * escapes to the caller.
 *
 * Previously only <clinit> was patched. Extending to all methods covers lazy-init patterns
 * where the method re-attempts the lookup at first use after the clinit has been caught.
 */
fun patchClassIfNeeded(classBytes: ByteArray): ByteArray {
    return try {
        val reader = ClassReader(classBytes)
        val classNode = ClassNode()
        reader.accept(classNode, ClassReader.EXPAND_FRAMES)

        var patched = false
        for (method in classNode.methods) {
            val hasGetAndAddInt = method.instructions.any { it is LdcInsnNode && it.cst == "getAndAddInt" }
            if (!hasGetAndAddInt) continue

            val tryStart   = LabelNode()
            val tryEnd     = LabelNode()
            val catchStart = LabelNode()

            method.instructions.insert(tryStart)
            method.instructions.add(tryEnd)
            method.instructions.add(catchStart)
            for (insn in returnInstructions(method.desc)) {
                method.instructions.add(insn)
            }

            if (method.tryCatchBlocks == null) method.tryCatchBlocks = mutableListOf()
            method.tryCatchBlocks.add(0, TryCatchBlockNode(tryStart, tryEnd, catchStart, "java/lang/Throwable"))
            patched = true
        }

        if (!patched) return classBytes

        val writer = object : ClassWriter(reader, ClassWriter.COMPUTE_FRAMES) {
            override fun getCommonSuperClass(type1: String, type2: String) = "java/lang/Object"
        }
        classNode.accept(writer)
        writer.toByteArray()
    } catch (_: Exception) {
        classBytes
    }
}

/**
 * Makes every overload of PluginManagerCore.registerExtensionPointAndExtensions a void no-op.
 *
 * Android has no javax.xml.stream (StAX) — it was never part of the platform. That method is
 * the only call site that triggers JDOMUtil → StAX → XMLInputFactory, which throws
 * NoClassDefFoundError on device. The extension implementations it loads are optional plugin
 * hooks; the compiler's core extension POINTS are registered separately by registerAppExtensionPoints()
 * and are unaffected. Basic Kotlin compilation works with an empty extension implementation list.
 */
fun patchPluginManagerCore(classBytes: ByteArray): ByteArray {
    return try {
        val reader = ClassReader(classBytes)
        val classNode = ClassNode()
        reader.accept(classNode, ClassReader.EXPAND_FRAMES)

        var patched = false
        for (method in classNode.methods) {
            if (method.name == "registerExtensionPointAndExtensions") {
                val noOp = InsnList()
                noOp.add(InsnNode(Opcodes.RETURN))
                method.instructions = noOp
                method.tryCatchBlocks = mutableListOf()
                method.localVariables = mutableListOf()
                patched = true
            }
        }

        if (!patched) return classBytes

        val writer = object : ClassWriter(reader, ClassWriter.COMPUTE_FRAMES) {
            override fun getCommonSuperClass(type1: String, type2: String) = "java/lang/Object"
        }
        classNode.accept(writer)
        writer.toByteArray()
    } catch (_: Exception) {
        classBytes
    }
}

/**
 * Wraps any method in CacheKt that calls Class.getMethods() in try-catch(Error).
 *
 * Android's Class.getMethods() sorts methods by calling Method.getReturnType() on all of them,
 * resolving every return type. IntelliJ platform classes reference javax.swing.Icon and other
 * Swing types absent on Android — getMethods() throws NoClassDefFoundError. The DI container
 * uses the setter list for optional injection; returning empty is safe for basic compilation.
 */
fun patchCacheKt(classBytes: ByteArray): ByteArray {
    return try {
        val reader = ClassReader(classBytes)
        val classNode = ClassNode()
        reader.accept(classNode, ClassReader.EXPAND_FRAMES)

        var patched = false
        for (method in classNode.methods) {
            val callsMethods = method.instructions.any { insn ->
                insn is MethodInsnNode &&
                insn.opcode == Opcodes.INVOKEVIRTUAL &&
                insn.owner == "java/lang/Class" &&
                insn.name == "getMethods"
            }
            if (!callsMethods) continue

            val tryStart = LabelNode()
            val tryEnd = LabelNode()
            val catchStart = LabelNode()

            method.instructions.insert(tryStart)
            method.instructions.add(tryEnd)
            method.instructions.add(catchStart)
            method.instructions.add(InsnNode(Opcodes.POP))
            method.instructions.add(MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/util/Collections",
                "emptyList",
                "()Ljava/util/List;",
                false,
            ))
            method.instructions.add(InsnNode(Opcodes.ARETURN))

            if (method.tryCatchBlocks == null) method.tryCatchBlocks = mutableListOf()
            method.tryCatchBlocks.add(0, TryCatchBlockNode(tryStart, tryEnd, catchStart, "java/lang/Error"))
            patched = true
        }

        if (!patched) return classBytes

        val writer = object : ClassWriter(reader, ClassWriter.COMPUTE_FRAMES) {
            override fun getCommonSuperClass(type1: String, type2: String) = "java/lang/Object"
        }
        classNode.accept(writer)
        writer.toByteArray()
    } catch (_: Exception) {
        classBytes
    }
}

/** Copies src JAR to dest, patching every .class entry through patchClassIfNeeded. */
/**
 * Compiles the classes in stubs/art-replacements (Java 8 bytecode) and returns them by jar entry
 * name. They replace same-named compiler classes that can't work on ART as shipped.
 */
fun compileArtReplacements(srcDir: File, outDir: File): Map<String, ByteArray> {
    val sources = srcDir.walkTopDown().filter { it.isFile && it.extension == "java" }.toList()
    if (sources.isEmpty()) return emptyMap()
    outDir.deleteRecursively()
    outDir.mkdirs()
    val javac = javax.tools.ToolProvider.getSystemJavaCompiler() ?: error("copyBuildTools needs a JDK (javac) to run Gradle")
    val args = listOf("-source", "8", "-target", "8", "-nowarn", "-Xlint:-options", "-d", outDir.absolutePath) + sources.map { it.absolutePath }
    check(javac.run(null, null, null, *args.toTypedArray()) == 0) { "Compiling ${srcDir.name} failed" }
    return outDir.walkTopDown().filter { it.isFile && it.extension == "class" }
        .associate { it.relativeTo(outDir).invariantSeparatorsPath to it.readBytes() }
}

/**
 * Points calls to JDK methods Android lacks at com.bori.compat.ProcessorCompat (in
 * processor-stubs-dex.jar, on the compiler's class loader). Same mapping as the app's
 * ProcessorRewriter, which does this for downloaded symbol processors. Null when unchanged.
 */
fun retargetCompatCalls(classBytes: ByteArray): ByteArray? {
    val text = String(classBytes, Charsets.ISO_8859_1)
    if (listOf("trySetAccessible", "java/beans/Introspector", "java/awt/EventQueue").none { it in text }) return null
    val compat = "com/bori/compat/ProcessorCompat"
    val reflectionOwners = setOf("java/lang/reflect/AccessibleObject", "java/lang/reflect/Constructor",
        "java/lang/reflect/Method", "java/lang/reflect/Field", "java/lang/reflect/Executable")
    val statics = mapOf(
        "java/beans/Introspector.decapitalize(Ljava/lang/String;)Ljava/lang/String;" to "decapitalize",
        "java/awt/EventQueue.isDispatchThread()Z" to "isDispatchThread",
        "java/awt/EventQueue.invokeLater(Ljava/lang/Runnable;)V" to "invokeLater",
        "java/awt/EventQueue.invokeAndWait(Ljava/lang/Runnable;)V" to "invokeAndWait",
    )
    val reader = ClassReader(classBytes)
    val writer = ClassWriter(reader, 0)
    var touched = false
    reader.accept(object : org.objectweb.asm.ClassVisitor(Opcodes.ASM9, writer) {
        override fun visitMethod(access: Int, name: String?, descriptor: String?, signature: String?, exceptions: Array<out String>?): org.objectweb.asm.MethodVisitor {
            val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
            return object : org.objectweb.asm.MethodVisitor(Opcodes.ASM9, mv) {
                override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, isInterface: Boolean) {
                    val static = statics["$owner.$name$desc"]
                    when {
                        opcode == Opcodes.INVOKEVIRTUAL && owner in reflectionOwners && name == "trySetAccessible" && desc == "()Z" -> {
                            touched = true
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, compat, "trySetAccessible", "(Ljava/lang/reflect/AccessibleObject;)Z", false)
                        }
                        opcode == Opcodes.INVOKESTATIC && static != null -> {
                            touched = true
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, compat, static, desc, false)
                        }
                        else -> super.visitMethodInsn(opcode, owner, name, desc, isInterface)
                    }
                }
            }
        }
    }, 0)
    return if (touched) writer.toByteArray() else null
}

/** Copies [src] to [dest] with retargetCompatCalls applied; returns the classes it changed. */
fun retargetCompatJar(src: File, dest: File): List<String> {
    val changed = mutableListOf<String>()
    ZipFile(src).use { zip ->
        ZipOutputStream(dest.outputStream().buffered()).use { zos ->
            for (entry in zip.entries()) {
                if (entry.isDirectory) continue
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                val out = if (entry.name.endsWith(".class")) retargetCompatCalls(bytes)?.also { changed += entry.name } ?: bytes else bytes
                zos.putNextEntry(ZipEntry(entry.name))
                zos.write(out)
                zos.closeEntry()
            }
        }
    }
    return changed
}

/**
 * JDK 21's SourceVersion works out the latest release with Runtime.version(), which Android
 * doesn't have, so the class fails to load. Its getLatestSupported() returns RELEASE_17 instead.
 */
fun patchSourceVersion(classBytes: ByteArray): ByteArray {
    val reader = ClassReader(classBytes)
    val node = ClassNode()
    reader.accept(node, 0)
    val method = node.methods.firstOrNull { it.name == "getLatestSupported" && it.desc == "()Ljavax/lang/model/SourceVersion;" }
        ?: return classBytes
    method.instructions.clear()
    method.tryCatchBlocks?.clear()
    method.localVariables?.clear()
    method.instructions.add(org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETSTATIC, "javax/lang/model/SourceVersion", "RELEASE_17", "Ljavax/lang/model/SourceVersion;"))
    method.instructions.add(InsnNode(Opcodes.ARETURN))
    val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
    node.accept(writer)
    return writer.toByteArray()
}

fun patchKotlinCompilerJar(src: File, dest: File, replacements: Map<String, ByteArray> = emptyMap()): List<String> {
    val patched = mutableListOf<String>()
    ZipInputStream(src.inputStream().buffered()).use { zin ->
        ZipOutputStream(dest.outputStream().buffered()).use { zout ->
            var entry = zin.nextEntry
            while (entry != null) {
                val bytes = zin.readBytes()
                val out = if (entry.name in replacements) {
                    patched.add(entry.name + " (replaced)")
                    replacements.getValue(entry.name)
                } else if (entry.name.endsWith(".class")) {
                    var fixed = patchClassIfNeeded(bytes)
                    if (entry.name == "org/jetbrains/kotlin/com/intellij/ide/plugins/PluginManagerCore.class") {
                        fixed = patchPluginManagerCore(fixed)
                    }
                    if (entry.name == "org/jetbrains/kotlin/container/CacheKt.class") {
                        fixed = patchCacheKt(fixed)
                    }
                    retargetCompatCalls(fixed)?.let { fixed = it }
                    if (fixed !== bytes) patched.add(entry.name)
                    fixed
                } else bytes
                zout.putNextEntry(ZipEntry(entry.name))
                zout.write(out)
                zout.closeEntry()
                zin.closeEntry()
                entry = zin.nextEntry
            }
        }
    }
    return patched
}

tasks.register("copyBuildTools") {
    // Declared inputs/outputs let Gradle skip this (slow) task when nothing changed,
    // instead of regenerating every asset on every build.
    inputs.files(configurations.named("buildToolsJars"), configurations.named("androidxLibs"))
    inputs.dir("stubs/art-replacements")
    inputs.dir("stubs/processor-stubs")
    inputs.file("stubs/swing-stubs.jar")
    inputs.property("compileSdk", pinnedCompileSdk)
    inputs.property("buildTools", pinnedBuildTools)
    outputs.files(generatedAssetNames.map { file("src/main/assets/$it") })

    doLast {
        val sdkDir = findAndroidSdk()
            ?: error("Android SDK not found. Set sdk.dir in local.properties or ANDROID_HOME.")
        val realAssetsDir = file("src/main/assets").also { it.mkdirs() }

        // Everything is generated into a fresh staging dir and only copied into
        // src/main/assets/ once all steps succeed, so a failure never leaves a half-built
        // bundle (and stale AndroidX DEX can't survive a version bump either).
        val assetsDir = File(buildDir, "build_tools_staging")
        assetsDir.deleteRecursively()
        assetsDir.mkdirs()

        // Run D8 on the JDK that runs Gradle, not whatever `java` is on PATH.
        val exeSuffix = if (System.getProperty("os.name").lowercase().contains("windows")) ".exe" else ""
        val javaExe = File(System.getProperty("java.home"), "bin/java$exeSuffix").absolutePath

        val latestBuildTools = File(sdkDir, "build-tools/$pinnedBuildTools")
        if (!latestBuildTools.isDirectory) {
            error("Android SDK build-tools $pinnedBuildTools not found in $sdkDir/build-tools. Install it once with " +
                "./gradlew :core:build:compileDebugKotlin -x :core:build:copyBuildTools (AGP downloads it).")
        }
        val latestPlatform = File(sdkDir, "platforms/android-$pinnedCompileSdk")
        if (!latestPlatform.isDirectory) {
            error("Android SDK platform android-$pinnedCompileSdk not found in $sdkDir/platforms.")
        }

        val sdkD8Jar = File(latestBuildTools, "lib/d8.jar")
        val androidJar = File(latestPlatform, "android.jar")
        val d8AssetJar = File(assetsDir, "d8.jar")

        // Helper to pre-dex and preserve original jar resources (classes, META-INF, etc.)
        val dexWithServices = { src: File, out: File ->
            val temp = File(buildDir, "temp_dex_${src.nameWithoutExtension}")
            temp.deleteRecursively()
            temp.mkdirs()

            // 1. Generate .dex files into temp directory
            exec {
                commandLine(
                    javaExe, "-cp", sdkD8Jar.absolutePath,
                    "com.android.tools.r8.D8",
                    "--output", temp.absolutePath,
                    "--min-api", "26",
                    "--lib", androidJar.absolutePath,
                    src.absolutePath,
                )
            }

            // 2. Write the .dex files plus the original jar's entries into the output jar
            // (pure Kotlin, so no platform-specific `jar` binary is needed)
            val written = mutableSetOf<String>()
            ZipOutputStream(out.outputStream().buffered()).use { zos ->
                temp.walkTopDown().filter { it.isFile }.forEach { dex ->
                    val name = dex.relativeTo(temp).invariantSeparatorsPath
                    written += name
                    zos.putNextEntry(ZipEntry(name))
                    dex.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
                ZipFile(src).use { zip ->
                    zip.entries().asSequence().filter { !it.isDirectory }.forEach { entry ->
                        if (!written.add(entry.name)) return@forEach
                        zos.putNextEntry(ZipEntry(entry.name))
                        zip.getInputStream(entry).use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
            temp.deleteRecursively()
        }

        // Pre-dex d8.jar with desugaring + --lib so invoke-custom lambdas are converted to
        // concrete inner classes — Android's LambdaMetafactory has a different signature than JVM's.
        exec {
            commandLine(
                javaExe, "-cp", sdkD8Jar.absolutePath,
                "com.android.tools.r8.D8",
                "--output", d8AssetJar.absolutePath,
                "--min-api", "26",
                "--lib", androidJar.absolutePath,
                sdkD8Jar.absolutePath,
            )
        }
        logger.lifecycle("Pre-dexed d8.jar from build-tools/${latestBuildTools.name}")

        // android-stubs.jar — reuse androidJar found above
        copy {
            from(androidJar)
            rename { "android-stubs.jar" }
            into(assetsDir)
        }
        logger.lifecycle("Copied android-stubs.jar from platforms/${latestPlatform.name}")

        // core-lambda-stubs.jar — provides java.lang.invoke.LambdaMetafactory for ECJ bootclasspath
        copy {
            from(File(latestBuildTools, "core-lambda-stubs.jar"))
            into(assetsDir)
        }
        logger.lifecycle("Copied core-lambda-stubs.jar from build-tools/${latestBuildTools.name}")

        // Locate kotlin-compiler-embeddable and kotlin-stdlib via dedicated resolvable config
        val artifacts = buildToolsJars.resolvedConfiguration.resolvedArtifacts
        val kotlincSrc = artifacts.first { it.name == "kotlin-compiler-embeddable" }.file
        logger.lifecycle("Using kotlinc artifact: ${kotlincSrc.name} (version: ${artifacts.first { it.name == "kotlin-compiler-embeddable" }.moduleVersion.id.version})")

        // Patch kotlin-compiler-embeddable: wrap <clinit> methods that look up
        // sun.misc.Unsafe.getAndAddInt in try-catch(Throwable) so Android 15's
        // blocked hidden-API enforcement doesn't cause ExceptionInInitializerError.
        val patchedKotlinc = File(buildDir, "patched_kotlinc_embeddable.jar")
        val artReplacements = compileArtReplacements(file("stubs/art-replacements"), File(buildDir, "art-replacements"))
        val patchedClasses = patchKotlinCompilerJar(kotlincSrc, patchedKotlinc, artReplacements)
        logger.lifecycle("Patched kotlinc: modified ${patchedClasses.size} class(es): $patchedClasses")
        dexWithServices(patchedKotlinc, File(assetsDir, "kotlinc.jar"))
        logger.lifecycle("Pre-dexed kotlinc.jar with services (patched)")

        // kotlin-stdlib as plain JAR — used on the -classpath flag at user-project compile time
        val stdlibSrc = artifacts.first { it.name == "kotlin-stdlib" }.file
        copy {
            from(stdlibSrc)
            rename { "kotlin-stdlib.jar" }
            into(assetsDir)
        }
        logger.lifecycle("Copied kotlin-stdlib from ${stdlibSrc.name}")

        // Pre-dex kotlin-stdlib so it can be loaded by PathClassLoader alongside kotlinc.
        exec {
            commandLine(
                javaExe, "-cp", sdkD8Jar.absolutePath,
                "com.android.tools.r8.D8",
                "--output", File(assetsDir, "kotlin-stdlib-dex.jar").absolutePath,
                "--min-api", "26",
                "--lib", androidJar.absolutePath,
                stdlibSrc.absolutePath,
            )
        }
        logger.lifecycle("Pre-dexed kotlin-stdlib-dex from ${stdlibSrc.name}")

        // Pre-dex kotlin-reflect — patch first so any getAndAddInt lookups are silenced
        val reflectSrc = artifacts.first { it.name == "kotlin-reflect" }.file
        val patchedReflect = File(buildDir, "patched_kotlin_reflect.jar")
        val patchedReflectClasses = patchKotlinCompilerJar(reflectSrc, patchedReflect)
        if (patchedReflectClasses.isNotEmpty())
            logger.lifecycle("Patched kotlin-reflect: modified ${patchedReflectClasses.size} class(es): $patchedReflectClasses")
        dexWithServices(patchedReflect, File(assetsDir, "kotlin-reflect-dex.jar"))
        logger.lifecycle("Pre-dexed kotlin-reflect-dex.jar with services (patched)")

        // Pre-dex trove4j — patch first (Trove uses Unsafe CAS atomics for primitive hash maps)
        val troveSrc = artifacts.first { it.name == "trove4j" }.file
        val patchedTrove = File(buildDir, "patched_trove4j.jar")
        val patchedTroveClasses = patchKotlinCompilerJar(troveSrc, patchedTrove)
        if (patchedTroveClasses.isNotEmpty())
            logger.lifecycle("Patched trove4j: modified ${patchedTroveClasses.size} class(es): $patchedTroveClasses")
        dexWithServices(patchedTrove, File(assetsDir, "trove4j-dex.jar"))
        logger.lifecycle("Pre-dexed trove4j-dex.jar with services (patched)")

        // Pre-dex swing-stubs so the on-device compiler can load javax.swing.* classes that
        // the IntelliJ platform references (e.g. HTMLEditorKit$ParserCallback).
        val swingStubsSrc = file("stubs/swing-stubs.jar")
        exec {
            commandLine(
                javaExe, "-cp", sdkD8Jar.absolutePath,
                "com.android.tools.r8.D8",
                "--output", File(assetsDir, "swing-stubs-dex.jar").absolutePath,
                "--min-api", "26",
                "--lib", androidJar.absolutePath,
                swingStubsSrc.absolutePath,
            )
        }
        logger.lifecycle("Pre-dexed swing-stubs-dex.jar")

        // org.jetbrains:annotations — needed by Kotlin compiler codegen when emitting
        // @Nullable/@NotNull annotations on generated method parameters on Android.
        val annotationsSrc = artifacts.first { it.name == "annotations" }.file
        exec {
            commandLine(
                javaExe, "-cp", sdkD8Jar.absolutePath,
                "com.android.tools.r8.D8",
                "--output", File(assetsDir, "jetbrains-annotations-dex.jar").absolutePath,
                "--min-api", "26",
                "--lib", androidJar.absolutePath,
                annotationsSrc.absolutePath,
            )
        }
        logger.lifecycle("Pre-dexed jetbrains-annotations-dex.jar")

        // kotlinx-coroutines: Kotlin 2.x's compiler (incremental compilation) needs it at run time.
        val coroutinesSrc = artifacts.first { it.name == "kotlinx-coroutines-core-jvm" }.file
        exec {
            commandLine(
                javaExe, "-cp", sdkD8Jar.absolutePath,
                "com.android.tools.r8.D8",
                "--output", File(assetsDir, "kotlinx-coroutines-dex.jar").absolutePath,
                "--min-api", "26",
                "--lib", androidJar.absolutePath,
                coroutinesSrc.absolutePath,
            )
        }
        logger.lifecycle("Pre-dexed kotlinx-coroutines-dex.jar from ${coroutinesSrc.name}")

        // Compiler plugins and KSP, shipped with the app instead of downloaded at build time:
        // the app may not download code to run itself (Google Play policy), and these run inside
        // the on-device compiler. Each keeps its original entries for -Xplugin's service lookup.
        for ((artifactName, assetName) in listOf(
            "kotlin-compose-compiler-plugin-embeddable" to "compose-plugin.jar",
            "kotlin-serialization-compiler-plugin-embeddable" to "serialization-plugin.jar",
            "kotlin-parcelize-compiler" to "parcelize-plugin.jar",
            "symbol-processing" to "ksp-plugin.jar",
            "symbol-processing-api" to "ksp-api.jar",
        )) {
            val original = artifacts.first { it.name == artifactName }.file
            val adapted = File(buildDir, "compat_${original.name}")
            val changed = retargetCompatJar(original, adapted)
            if (changed.isNotEmpty()) logger.lifecycle("Retargeted JDK calls in ${changed.size} class(es) of $artifactName")
            dexWithServices(adapted, File(assetsDir, assetName))
            logger.lifecycle("Pre-dexed $assetName")
        }
        // KSP's own runtime dependencies (kotlinx-serialization).
        exec {
            commandLine(
                listOf(javaExe, "-cp", sdkD8Jar.absolutePath, "com.android.tools.r8.D8",
                    "--output", File(assetsDir, "ksp-deps-dex.jar").absolutePath,
                    "--min-api", "26", "--lib", androidJar.absolutePath) +
                    artifacts.filter { it.name.startsWith("kotlinx-serialization-") }.map { it.file.absolutePath }
            )
        }
        logger.lifecycle("Pre-dexed ksp-deps-dex.jar")

        // JDK API classes Android lacks (GPLv2 with the Classpath Exception):
        // - javax.lang.model / javax.annotation.processing / javax.tools (java.compiler): JavaPoet,
        //   which Room, Dagger and Hilt generate code with, uses them;
        // - six javax.xml.stream interfaces (java.xml): the Kotlin 2 compiler reads its plugin
        //   descriptors with the StAX parser it bundles (Aalto), which implements them.
        val javaCompilerApi = File(buildDir, "java-compiler-api.jar")
        val jrt = FileSystems.getFileSystem(URI.create("jrt:/"))
        val staxApi = listOf("Location", "XMLReporter", "XMLResolver", "XMLStreamConstants", "XMLStreamException", "XMLStreamReader")
        ZipOutputStream(javaCompilerApi.outputStream().buffered()).use { zos ->
            val moduleRoot = jrt.getPath("/modules/java.compiler")
            Files.walk(moduleRoot).use { paths ->
                paths.filter { p -> p.toString().endsWith(".class") && !p.toString().endsWith("module-info.class") }
                    .sorted()
                    .forEach { path ->
                        val name = moduleRoot.relativize(path).toString()
                        zos.putNextEntry(ZipEntry(name))
                        val bytes = Files.readAllBytes(path)
                        zos.write(if (name == "javax/lang/model/SourceVersion.class") patchSourceVersion(bytes) else bytes)
                        zos.closeEntry()
                    }
            }
            for (name in staxApi) {
                zos.putNextEntry(ZipEntry("javax/xml/stream/$name.class"))
                Files.newInputStream(jrt.getPath("/modules/java.xml/javax/xml/stream/$name.class")).use { input -> input.copyTo(zos) }
                zos.closeEntry()
            }
        }
        exec {
            commandLine(
                javaExe, "-cp", sdkD8Jar.absolutePath,
                "com.android.tools.r8.D8",
                "--output", File(assetsDir, "java-compiler-api-dex.jar").absolutePath,
                "--min-api", "26",
                "--lib", androidJar.absolutePath,
                javaCompilerApi.absolutePath,
            )
        }
        logger.lifecycle("Pre-dexed java-compiler-api-dex.jar (JDK java.compiler + StAX API)")

        // Stand-ins that symbol processors load ahead of their own dependencies (stubs/processor-stubs).
        val processorStubsDir = File(buildDir, "processor-stubs")
        val processorStubs = compileArtReplacements(file("stubs/processor-stubs"), processorStubsDir)
        val processorStubsJar = File(buildDir, "processor-stubs.jar")
        ZipOutputStream(processorStubsJar.outputStream().buffered()).use { zos ->
            processorStubs.toSortedMap().forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        exec {
            commandLine(
                javaExe, "-cp", sdkD8Jar.absolutePath,
                "com.android.tools.r8.D8",
                "--output", File(assetsDir, "processor-stubs-dex.jar").absolutePath,
                "--min-api", "26",
                "--lib", androidJar.absolutePath,
                processorStubsJar.absolutePath,
            )
        }
        logger.lifecycle("Pre-dexed processor-stubs-dex.jar (${processorStubs.size} class(es))")

        // ---- AndroidX library bundle ----
        logger.lifecycle("Building AndroidX library bundle (this may take a few minutes)...")
        val androidxWorkDir = File(buildDir, "androidx_bundle_work")
        androidxWorkDir.deleteRecursively()
        androidxWorkDir.mkdirs()

        val allClassesJars = mutableListOf<File>()
        val aarExtractDirs = mutableListOf<File>()

        // group:artifact:version of every bundled library, so the builder can tell when a project
        // needs a newer one (the jars' own META-INF/*.version files aren't always filled in).
        File(assetsDir, "androidx-versions.txt").writeText(
            androidxLibs.resolvedConfiguration.resolvedArtifacts
                .map { "${it.moduleVersion.id.group}:${it.moduleVersion.id.name}:${it.moduleVersion.id.version}" }
                .distinct().sorted().joinToString("\n")
        )

        // Resolve all AndroidX artifacts. A bare configuration (no consumer attributes) should
        // return raw .aar files for Android libraries. We handle both .aar and .jar defensively.
        androidxLibs.resolvedConfiguration.resolvedArtifacts.forEach { artifact ->
            val file = artifact.file
            val key = "${artifact.name}-${artifact.moduleVersion.id.version}"
            logger.lifecycle("  Resolved: $key (${file.extension})")
            when (file.extension.lowercase()) {
                "aar" -> {
                    val extractDir = File(androidxWorkDir, "aar_$key")
                    extractDir.mkdirs()
                    extractZipTo(file, extractDir)
                    val classesJar = File(extractDir, "classes.jar")
                    if (classesJar.exists()) allClassesJars.add(classesJar)
                    // Only add dirs that actually have res/ (needed for AAPT2 + package names)
                    aarExtractDirs.add(extractDir)
                }
                "jar" -> {
                    // Transformed JAR (classes only) — usable for fat JAR but no res/
                    allClassesJars.add(file)
                }
            }
        }
        logger.lifecycle("Found ${allClassesJars.size} class JAR(s) from ${aarExtractDirs.size} AAR(s)")

        // A. Merge all classes into fat JAR (exclude R, BuildConfig, duplicate META-INF entries)
        val kotlinStdlibPrefixes = setOf("kotlin/", "kotlin-stdlib", "META-INF/kotlin-stdlib",
            "META-INF/services/kotlin.")
        val seenEntries = mutableSetOf<String>()
        val androidxClassesJarFile = File(assetsDir, "androidx-classes.jar")
        ZipOutputStream(androidxClassesJarFile.outputStream().buffered()).use { zos ->
            allClassesJars.forEach { jar ->
                ZipFile(jar).use { zipFile ->
                    zipFile.entries().asSequence().forEach { entry ->
                        if (entry.isDirectory) return@forEach
                        val name = entry.name
                        // Skip R classes, BuildConfig, and duplicate entries
                        if (name.matches(Regex(".*/R\\.class|.*/R\\$[^/]+\\.class|.*/BuildConfig\\.class"))) return@forEach
                        if (name == "META-INF/MANIFEST.MF") return@forEach
                        // Skip Kotlin stdlib classes (bundled separately)
                        if (kotlinStdlibPrefixes.any { name.startsWith(it) }) return@forEach
                        if (!seenEntries.add(name)) return@forEach  // skip exact duplicates
                        zos.putNextEntry(ZipEntry(name))
                        zipFile.getInputStream(entry).copyTo(zos)
                        zos.closeEntry()
                    }
                }
            }
        }
        logger.lifecycle("Built androidx-classes.jar (${androidxClassesJarFile.length() / 1024} KB from ${allClassesJars.size} source JARs)")

        // B. Pre-dex the fat JAR → classes2.dex (on-device: library DEX, no D8 at runtime)
        val androidxDexOutDir = File(androidxWorkDir, "androidx_dex")
        androidxDexOutDir.mkdirs()
        exec {
            commandLine(
                javaExe, "-cp", sdkD8Jar.absolutePath,
                "com.android.tools.r8.D8",
                "--output", androidxDexOutDir.absolutePath,
                "--min-api", "21",
                "--lib", androidJar.absolutePath,
                androidxClassesJarFile.absolutePath,
            )
        }
        File(androidxDexOutDir, "classes.dex").copyTo(File(assetsDir, "androidx-classes2.dex"), overwrite = true)
        logger.lifecycle("Pre-dexed androidx-classes2.dex (${File(assetsDir, "androidx-classes2.dex").length() / 1024} KB)")

        // C. AAPT2 compile all library res/ → pre-compiled .flat files bundled as ZIP
        // Each library compiles into its own subdirectory to prevent name collisions (e.g. multiple
        // libraries all produce values_values.arsc.flat — compiling to a shared dir causes last-wins).
        val aapt2Exe = File(latestBuildTools, "aapt2$exeSuffix")
        val libResCompiledDir = File(androidxWorkDir, "lib_res_compiled")
        libResCompiledDir.mkdirs()
        var totalResFiles = 0
        val allFlatFiles = mutableListOf<Pair<String, File>>() // safeName → File

        aarExtractDirs.forEach { aarDir ->
            val resDir = File(aarDir, "res")
            if (!resDir.exists()) return@forEach
            val resFiles = resDir.walkTopDown().filter { it.isFile }.toList()
            if (resFiles.isEmpty()) return@forEach
            totalResFiles += resFiles.size

            // Compile each library to its own subdir to avoid overwriting other libraries' flat files
            val libName = aarDir.name  // e.g. "aar_appcompat-1.7.0"
            val libOut = File(libResCompiledDir, libName).also { it.mkdirs() }

            // ConstraintLayout 2.2 ships a Carousel widget whose <declare-styleable> duplicates
            // one in Material 1.12 — strip it entirely. The layout_constraint* attrs that
            // Material layouts reference live in the separate ConstraintLayout_Layout styleable
            // (a different block in the same values.xml) and are NOT removed here.
            if (libName.contains("constraintlayout")) {
                val valuesXml = File(resDir, "values/values.xml")
                if (valuesXml.exists()) {
                    val original = valuesXml.readText()
                    val patched = original.replace(
                        Regex("""<declare-styleable name="Carousel[^"]*">[\s\S]*?</declare-styleable>"""),
                        ""
                    )
                    if (patched != original) {
                        valuesXml.writeText(patched)
                        logger.lifecycle("Stripped Carousel styleable(s) from $libName values.xml")
                    }
                }
            }

            // Material defines SearchView styleable; appcompat's duplicate wrapper causes a hard
            // AAPT2 link conflict. Unwrap it: keep the inner <attr> declarations (needed by
            // Widget.AppCompat.SearchView styles) but remove the <declare-styleable> tags so
            // Material's copy is the only declared styleable.
            if (libName.contains("appcompat")) {
                val valuesXml = File(resDir, "values/values.xml")
                if (valuesXml.exists()) {
                    val original = valuesXml.readText()
                    val searchViewRegex = Regex("""<declare-styleable name="SearchView">([\s\S]*?)</declare-styleable>""")
                    val match = searchViewRegex.find(original)
                    val patched = if (match != null) {
                        // Keep only <attr> elements that declare a format= — appcompat's own
                        // attr declarations (layout, queryBackground, etc.) that styles reference.
                        // Drop bare references like <attr name="android:maxWidth" /> — these are
                        // framework attr references, only valid inside a <declare-styleable>;
                        // as standalone elements they cause "invalid entry name" errors in aapt2.
                        val declaredAttrs = Regex("""<attr\s[^>]*format=[^>]*/?>""")
                            .findAll(match.groupValues[1])
                            .joinToString("\n") { it.value }
                        original.replace(match.value, declaredAttrs)
                    } else original
                    if (patched != original) {
                        valuesXml.writeText(patched)
                        logger.lifecycle("Unwrapped SearchView styleable attrs from $libName values.xml")
                    }
                }
            }

            exec {
                commandLine(
                    aapt2Exe.absolutePath,
                    "compile",
                    "--dir", resDir.absolutePath,
                    "-o", libOut.absolutePath,
                )
            }
            // Collect flat files with a library-prefixed safe name so no two libraries collide
            libOut.walkTopDown().filter { it.isFile && it.name.endsWith(".flat") }.forEach { flat ->
                val relName = flat.relativeTo(libOut).invariantSeparatorsPath.replace('/', '_')
                val safeName = "${libName}_${relName}"
                allFlatFiles.add(safeName to flat)
            }
        }

        val seenNames = mutableSetOf<String>()
        ZipOutputStream(File(assetsDir, "androidx-res-compiled.zip").outputStream().buffered()).use { zos ->
            allFlatFiles.forEach { (safeName, flat) ->
                if (seenNames.add(safeName)) {
                    zos.putNextEntry(ZipEntry(safeName))
                    flat.inputStream().copyTo(zos)
                    zos.closeEntry()
                }
            }
        }
        logger.lifecycle("AAPT2-compiled $totalResFiles res file(s) → ${allFlatFiles.size} flat file(s) → androidx-res-compiled.zip")

        // D. Extract namespace package names (only from AARs with non-empty res/)
        val packages = mutableListOf<String>()
        aarExtractDirs.forEach { aarDir ->
            val resDir = File(aarDir, "res")
            if (!resDir.exists() || resDir.walkTopDown().filter { it.isFile }.none()) return@forEach
            val manifest = File(aarDir, "AndroidManifest.xml")
            if (!manifest.exists()) return@forEach
            val pkg = Regex("""package\s*=\s*["']([^"']+)["']""").find(manifest.readText())?.groupValues?.get(1)
            if (!pkg.isNullOrBlank()) packages.add(pkg)
        }
        File(assetsDir, "androidx-packages.txt").writeText(packages.distinct().joinToString("\n"))
        logger.lifecycle("Extracted ${packages.distinct().size} library package name(s) for --extra-packages")

        // E. Extract manifest permission/feature entries from library manifests
        val dbf = DocumentBuilderFactory.newInstance()
        val db = dbf.newDocumentBuilder()
        val allPermissions = mutableSetOf<String>()
        val allFeatures = mutableSetOf<String>()
        aarExtractDirs.forEach { aarDir ->
            val manifest = File(aarDir, "AndroidManifest.xml")
            if (!manifest.exists()) return@forEach
            try {
                val root = db.parse(manifest).documentElement
                val children = root.childNodes
                for (i in 0 until children.length) {
                    val node = children.item(i)
                    if (node !is Element) continue
                    when (node.nodeName) {
                        "uses-permission" -> {
                            val name = node.getAttribute("android:name")
                            if (name.isNotBlank()) allPermissions.add(name)
                        }
                        "uses-feature" -> {
                            val name = node.getAttribute("android:name")
                            if (name.isNotBlank()) allFeatures.add(name)
                        }
                    }
                }
            } catch (_: Exception) { /* skip malformed manifests */ }
        }
        val entriesSb = StringBuilder("<manifest-entries>\n")
        allPermissions.forEach { entriesSb.append("    <uses-permission android:name=\"$it\"/>\n") }
        allFeatures.forEach { entriesSb.append("    <uses-feature android:name=\"$it\"/>\n") }
        entriesSb.append("</manifest-entries>")
        File(assetsDir, "androidx-manifest-entries.xml").writeText(entriesSb.toString())
        logger.lifecycle("Extracted ${allPermissions.size} permission(s), ${allFeatures.size} feature(s) from library manifests")

        androidxWorkDir.deleteRecursively()
        logger.lifecycle("AndroidX library bundle complete!")

        // All steps succeeded — publish the staged assets
        generatedAssetNames.forEach { name ->
            File(assetsDir, name).copyTo(File(realAssetsDir, name), overwrite = true)
        }
        assetsDir.deleteRecursively()
        logger.lifecycle("Published ${generatedAssetNames.size} build-tool assets to src/main/assets")
    }
}

afterEvaluate {
    tasks.named("preBuild") { dependsOn("copyBuildTools") }
}

android {
    namespace = "com.bori.core.build"
    compileSdk = pinnedCompileSdk
    buildToolsVersion = pinnedBuildTools

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/BCKEY.DSA",
                "META-INF/BCKEY.SF",
                "META-INF/BCCERTS.SF",
            )
        }
    }
}

val buildToolsJars: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

// Resolves AndroidX library AARs/JARs for asset bundle generation at build time.
// Excludes Kotlin stdlib (bundled separately as kotlin-stdlib.jar / kotlin-stdlib-dex.jar).
val androidxLibs: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-common")
    // Pin lifecycle to 2.6.2 + fragment to 1.6.2 + activity to 1.7.2.
    // Root cause: appcompat:1.7.0 transitively upgrades lifecycle to 2.8.x, which REMOVED
    // the ViewModelProvider(ViewModelStore, Factory) 2-arg constructor (deleted in 2.8.0-rc01).
    // fragment:1.6.2 bytecode calls that 2-arg constructor → NoSuchMethodError at runtime.
    // Fix: force ALL lifecycle deps to 2.6.2 (which HAS the 2-arg constructor) and keep
    // fragment/activity at versions compiled against lifecycle 2.6.x. appcompat:1.6.1 is used
    // instead of 1.7.0 so its own transitive graph doesn't re-introduce lifecycle 2.8.x.
    resolutionStrategy {
        force("androidx.fragment:fragment:1.6.2")
        force("androidx.lifecycle:lifecycle-viewmodel:2.6.2")
        force("androidx.lifecycle:lifecycle-viewmodel-savedstate:2.6.2")
        force("androidx.lifecycle:lifecycle-livedata:2.6.2")
        force("androidx.lifecycle:lifecycle-livedata-core:2.6.2")
        force("androidx.lifecycle:lifecycle-runtime:2.6.2")
        force("androidx.lifecycle:lifecycle-common:2.6.2")
        force("androidx.activity:activity:1.7.2")
        force("androidx.savedstate:savedstate:1.2.1")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(project(":core:project"))

    // Java compiler (ECJ)
    implementation(libs.ecj)

    // Swing stubs for IntelliJ platform compatibility on Android
    implementation(files("stubs/swing-stubs.jar"))

    // Kotlin compiler + stdlib — compileOnly keeps them off the runtime classpath;
    // they are pre-dexed / copied by copyBuildTools and bundled as assets instead.
    compileOnly(libs.kotlin.compiler.embeddable)
    compileOnly(libs.kotlin.stdlib)

    // Resolvable config used only by copyBuildTools to locate the JARs on disk
    buildToolsJars(libs.kotlin.compiler.embeddable)
    buildToolsJars(libs.kotlin.stdlib.asset)
    buildToolsJars(libs.kotlin.reflect.asset)
    buildToolsJars(libs.trove4j)
    buildToolsJars("org.jetbrains:annotations:13.0")
    val kotlinAsset = libs.versions.kotlin.compiler.asset.get()
    buildToolsJars("org.jetbrains.kotlin:kotlin-compose-compiler-plugin-embeddable:$kotlinAsset")
    buildToolsJars("org.jetbrains.kotlin:kotlin-serialization-compiler-plugin-embeddable:$kotlinAsset")
    buildToolsJars("org.jetbrains.kotlin:kotlin-parcelize-compiler:$kotlinAsset")
    buildToolsJars("com.google.devtools.ksp:symbol-processing:${libs.versions.ksp.asset.get()}")
    buildToolsJars("com.google.devtools.ksp:symbol-processing-api:${libs.versions.ksp.asset.get()}")

    // BouncyCastle for APK signing (v1 JAR signing / PKCS7)
    implementation(libs.bcprov)
    implementation(libs.bcpkix)
    // APK Signature Scheme v1-v3 (Apache-2.0), compiled into the app itself
    implementation(libs.apksig)
    // Rewrites symbol processors' calls to JDK methods Android lacks (BSD-3-Clause)
    implementation(libs.asm)

    // AndroidX library bundle — resolved by copyBuildTools to generate pre-processed assets.
    // NOT on the runtime classpath; libraries are bundled as assets (androidx-classes.jar etc.)
    androidxLibs("androidx.core:core:1.15.0")
    androidxLibs("androidx.core:core-ktx:1.15.0")
    androidxLibs("androidx.appcompat:appcompat:1.6.1")           // 1.6.1 = lifecycle 2.6.x, NOT 1.7.0 which pulls lifecycle 2.8.x
    androidxLibs("com.google.android.material:material:1.12.0")
    androidxLibs("androidx.constraintlayout:constraintlayout:2.2.0")
    androidxLibs("androidx.recyclerview:recyclerview:1.4.0")
    androidxLibs("androidx.activity:activity:1.7.2")             // lifecycle 2.6.x compatible
    androidxLibs("androidx.fragment:fragment:1.6.2")             // uses 2-arg ViewModelProvider(ViewModelStore, Factory)
    androidxLibs("androidx.lifecycle:lifecycle-common:2.6.2")
    androidxLibs("androidx.lifecycle:lifecycle-viewmodel:2.6.2")
    androidxLibs("androidx.lifecycle:lifecycle-viewmodel-savedstate:2.6.2")
    androidxLibs("androidx.lifecycle:lifecycle-livedata:2.6.2")
    androidxLibs("androidx.lifecycle:lifecycle-livedata-core:2.6.2")
    androidxLibs("androidx.lifecycle:lifecycle-runtime:2.6.2")
}
