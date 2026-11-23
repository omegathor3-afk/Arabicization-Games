package com.omega.arabify

import java.io.File
import org.junit.Test

/**
 * Writes the hand-built SerializedFile fixtures into build/realFixtures/ so
 * the sandbox can validate them against UnityPy after the test run. The
 * assertions live in the UnityPy check recorded in docs/agent-progress.md.
 */
class FixtureDumpForUnityPyTest {
    @Test
    fun dumpFixturesForUnityPyValidation() {
        val dir = File("build/realFixtures")
        dir.mkdirs()
        File(dir, "fixture_v17.assets").writeBytes(
            UnityTestFixtures.serializedFileV17(
                listOf(
                    UnityTestFixtures.TextAssetSpec(1L, "menu.txt", "Start Game\nOptions"),
                    UnityTestFixtures.TextAssetSpec(2L, "dialog.txt", "The gate is locked.")
                )
            )
        )
        File(dir, "fixture_v22.assets").writeBytes(
            UnityTestFixtures.serializedFileV22(
                listOf(
                    UnityTestFixtures.TextAssetSpec(1L, "menu.txt", "ابدأ اللعبة\nالخيارات"),
                    UnityTestFixtures.TextAssetSpec(2L, "dialog.txt", "الباب مغلق.")
                )
            )
        )
    }
}
