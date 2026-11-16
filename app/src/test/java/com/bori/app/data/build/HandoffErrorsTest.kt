package com.bori.app.data.build

import com.bori.core.project.agent.AgentFiles
import org.junit.Assert.assertEquals
import org.junit.Test

class HandoffErrorsTest {
    @Test
    fun errorLinesAreKeptAndPathsShortened() {
        val root = "/storage/emulated/0/Android/data/com.bori.code.debug/files/Bori/Demo"
        val log = listOf(
            "▶ Kotlin compile",
            "warning: [ksp] $root/app/src/Db.kt:51: Room cannot create an SQLite connection. Error: SQLite isn't available",
            "storage/emulated/0/Android/data/com.bori.code.debug/files/Bori/Demo/app/src/A.kt:177:69: error: unresolved reference 'X'.",
            "D8: Warning: Unexpected error during rewriting of Kotlin metadata for class 'a.B':",
            "✗ Kotlin compilation failed — check errors above",
        )
        assertEquals(
            listOf(
                "app/src/A.kt:177:69: error: unresolved reference 'X'.",
                "✗ Kotlin compilation failed — check errors above",
            ),
            AgentFiles.errorLines(log, root),
        )
    }
}
