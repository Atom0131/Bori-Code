package com.bori.core.build.steps

import com.bori.core.build.BuildEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class LogTidyTest {
    @Test
    fun cachedLibrariesBecomeOneLine() {
        val log = listOf(
            "Resolving com.google.android.material:material:1.12.0…",
            "  ✓ Cached: material-1.12.0.aar",
            "Resolving org.example:new:1.0…",
            "  ↓ Downloaded: new-1.0.jar (12 KB)",
            "Resolving androidx.core:core-ktx:1.13.1…",
            "  ✓ Cached: core-ktx-1.13.1.aar",
            "⚠ Failed to resolve x:y:1: gone",
        )
        assertEquals(
            listOf(
                "Resolving org.example:new:1.0…",
                "  ↓ Downloaded: new-1.0.jar (12 KB)",
                "⚠ Failed to resolve x:y:1: gone",
                "✓ 2 libraries already downloaded",
            ),
            BuildEngine.tidyResolveLog(log),
        )
    }

    @Test
    fun repeatedKotlinMetadataWarningsAreCounted() {
        val err = listOf(
            "Warning: Unexpected error during rewriting of Kotlin metadata for class 'a.A':",
            "\tat x.y(Z.java:1)",
            "Warning: Unexpected error during rewriting of Kotlin metadata for class 'a.B':",
            "Warning: Unexpected error during rewriting of Kotlin metadata for class 'a.C':",
            "Error: real problem",
        )
        assertEquals(
            listOf(
                "Warning: Unexpected error during rewriting of Kotlin metadata for class 'a.A':",
                "…the same warning for 2 more classes (harmless: the app still works)",
                "Error: real problem",
            ),
            DexStep.tidyToolOutput(err),
        )
    }
}
