package com.bori.core.project.scaffold

import com.bori.core.project.model.Project
import java.io.File

object ProjectScaffold {

    /**
     * Creates the standard Android project directory tree and template files on disk.
     *
     * Structure:
     * ```
     * <rootPath>/
     *   app/src/main/java/<package path>/MainActivity.java
     *   app/src/main/res/layout/activity_main.xml
     *   app/src/main/res/values/strings.xml
     *   app/src/main/res/values/themes.xml
     *   app/AndroidManifest.xml
     *   build.gradle.kts
     *   settings.gradle.kts
     * ```
     */
    fun scaffold(project: Project) {
        val root = File(project.rootPath)

        val packagePath = project.packageName.replace('.', '/')
        val javaDir = File(root, "app/src/main/java/$packagePath")
        val resLayoutDir = File(root, "app/src/main/res/layout")
        val resValuesDir = File(root, "app/src/main/res/values")
        val appDir = File(root, "app")

        listOf(javaDir, resLayoutDir, resValuesDir).forEach { it.mkdirs() }

        // Source file (Java or Kotlin)
        val isKotlin = project.template.name.endsWith("_KOTLIN")
        if (isKotlin) {
            File(javaDir, "MainActivity.kt").writeText(
                TemplateFiles.mainKotlinActivity(project.packageName, project.template)
            )
        } else {
            File(javaDir, "MainActivity.java").writeText(
                TemplateFiles.mainActivity(project.packageName, project.template)
            )
        }

        // XML layouts
        File(resLayoutDir, "activity_main.xml").writeText(
            TemplateFiles.activityMainXml(project.template)
        )

        // Resource values
        File(resValuesDir, "strings.xml").writeText(
            TemplateFiles.stringsXml(project.name)
        )
        File(resValuesDir, "themes.xml").writeText(
            TemplateFiles.themesXml(project.name)
        )

        // AndroidManifest
        File(appDir, "AndroidManifest.xml").writeText(
            TemplateFiles.androidManifest(project.packageName)
        )

        // Gradle files — app/build.gradle.kts is read by BuildEngine and EditorViewModel
        File(appDir, "build.gradle.kts").writeText(
            TemplateFiles.appBuildGradleKts(project.packageName, project.minSdk)
        )
        File(root, "build.gradle.kts").writeText(
            TemplateFiles.rootBuildGradleKts()
        )
        File(root, "settings.gradle.kts").writeText(
            TemplateFiles.settingsGradleKts(project.name)
        )
        File(root, ".gitignore").writeText(
            TemplateFiles.gitignore()
        )
        File(appDir, "proguard-rules.pro").writeText(
            TemplateFiles.proguardRules()
        )
    }
}
