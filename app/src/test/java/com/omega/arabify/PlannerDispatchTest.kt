package com.omega.arabify

import java.io.File
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerDispatchTest {

    private fun buildApk(dir: File, name: String, entries: Map<String, String>): File {
        val apk = File(dir, name)
        ZipOutputStream(apk.outputStream().buffered()).use { zip ->
            entries.forEach { (entryName, content) ->
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return apk
    }

    private fun ref(apk: File): ArtifactRef =
        ArtifactRef(id = apk.name, displayName = apk.name, uri = apk.absolutePath, kind = ArtifactKind.APK, sizeBytes = apk.length())

    /** Minimal fake adapter: matched probe, configurable operations and extract behavior. */
    private class FakeAdapter(
        id: String,
        priority: Int,
        operations: Set<AdapterOperation>,
        private val onExtract: suspend () -> AdapterReport,
    ) : LocalizationAdapter {
        override val descriptor = AdapterDescriptor(
            id = id,
            version = "1.0",
            displayName = id,
            inputKinds = setOf(ArtifactKind.APK),
            outputKinds = setOf(ArtifactKind.APK),
            operations = operations,
            priority = priority,
        )

        override suspend fun probe(input: ArtifactRef, budget: ResourceBudget): AdapterProbe =
            AdapterProbe(descriptor.id, matched = true, confidence = 100)

        override suspend fun extract(input: ArtifactRef, budget: ResourceBudget): AdapterReport = onExtract()
    }

    private fun okReport(id: String) = AdapterReport(id, AdapterOperation.EXTRACT, processed = 1, succeeded = 1, skipped = 0)

    @Test
    fun dispatchPicksHighestPriorityUsableAdapter() {
        runBlocking {
            val dir = createTempDir()
            val apk = buildApk(dir, "a.apk", mapOf("AndroidManifest.xml" to "binary"))
            val registry = LocalizationAdapterRegistry(listOf(
                FakeAdapter("low", 0, setOf(AdapterOperation.EXTRACT)) { okReport("low") },
                FakeAdapter("high", 300, setOf(AdapterOperation.EXTRACT)) { okReport("high") },
            ))
            val result = PlannerDispatch(registry).dispatch(ref(apk), ResourceBudget())
            assertEquals("high", result.adapterId)
            assertEquals(listOf("high"), result.attempted)
            dir.deleteRecursively()
            Unit
        }
    }

    @Test
    fun dispatchFallsBackToNextUsableAdapterOnThrow() {
        runBlocking {
            val dir = createTempDir()
            val apk = buildApk(dir, "a.apk", mapOf("AndroidManifest.xml" to "binary"))
            val registry = LocalizationAdapterRegistry(listOf(
                FakeAdapter("fallback", 0, setOf(AdapterOperation.EXTRACT)) { okReport("fallback") },
                FakeAdapter("failing", 300, setOf(AdapterOperation.EXTRACT)) { throw IllegalStateException("no slot convention") },
            ))
            val result = PlannerDispatch(registry).dispatch(ref(apk), ResourceBudget())
            assertEquals("fallback", result.adapterId)
            assertEquals(listOf("failing", "fallback"), result.attempted)
            dir.deleteRecursively()
            Unit
        }
    }

    @Test
    fun dispatchRethrowsCancellationInsteadOfFallingBack() {
        runBlocking {
            val dir = createTempDir()
            val apk = buildApk(dir, "a.apk", mapOf("AndroidManifest.xml" to "binary"))
            val registry = LocalizationAdapterRegistry(listOf(
                FakeAdapter("next", 0, setOf(AdapterOperation.EXTRACT)) { okReport("next") },
                FakeAdapter("cancelled", 300, setOf(AdapterOperation.EXTRACT)) { throw CancellationException("job cancelled") },
            ))
            assertThrows(CancellationException::class.java) {
                runBlocking { PlannerDispatch(registry).dispatch(ref(apk), ResourceBudget()) }
            }
            dir.deleteRecursively()
            Unit
        }
    }

    @Test
    fun dispatchSkipsAdaptersWithoutExtractOperation() {
        runBlocking {
            val dir = createTempDir()
            val apk = buildApk(dir, "a.apk", mapOf("AndroidManifest.xml" to "binary"))
            val registry = LocalizationAdapterRegistry(listOf(
                FakeAdapter("writer", 0, setOf(AdapterOperation.EXTRACT)) { okReport("writer") },
                FakeAdapter("inspect-only", 300, setOf(AdapterOperation.INSPECT)) {
                    throw AssertionError("INSPECT-only adapter must never be asked to EXTRACT")
                },
            ))
            val result = PlannerDispatch(registry).dispatch(ref(apk), ResourceBudget())
            assertEquals("writer", result.adapterId)
            assertEquals(listOf("writer"), result.attempted)
            dir.deleteRecursively()
            Unit
        }
    }

    @Test
    fun dispatchThrowsWithAttemptsAndFailuresWhenAllFail() {
        runBlocking {
            val dir = createTempDir()
            val apk = buildApk(dir, "a.apk", mapOf("AndroidManifest.xml" to "binary"))
            val registry = LocalizationAdapterRegistry(listOf(
                FakeAdapter("b", 0, setOf(AdapterOperation.EXTRACT)) { throw IllegalStateException("b broke") },
                FakeAdapter("a", 300, setOf(AdapterOperation.EXTRACT)) { throw IllegalStateException("a broke") },
            ))
            val error = assertThrows(LocalizationDispatchException::class.java) {
                runBlocking { PlannerDispatch(registry).dispatch(ref(apk), ResourceBudget()) }
            }
            assertEquals(listOf("a", "b"), error.attempted)
            assertEquals(2, error.failures.size)
            assertTrue(error.message!!.contains("a broke"))
            assertTrue(error.message!!.contains("b broke"))
            dir.deleteRecursively()
            Unit
        }
    }

    @Test
    fun planOnRealRegistryOrdersSlotFixtureByTodaysChain() {
        runBlocking {
            // End-to-end on the real wrappers: a fixture matching every
            // convention must plan in today's chain order (slot -> gettext
            // -> resources -> in-place). Phase A wrappers declare INSPECT
            // only, so dispatch() has nothing to attempt yet; the PLAN order
            // is the behavior this pins until Phase B adds extract().
            val dir = createTempDir()
            val apk = buildApk(dir, "gdx.apk", mapOf(
                "assets/bundle.properties" to "Play=Play",
                "assets/po/messages.pot" to "msgid \"Play\"\nmsgstr \"\"\n",
                "assets/ui/languages.xml" to "<Languages/>",
                "resources.arsc" to "not-a-real-table",
                "assets/data/text.csv" to "Hello",
                "AndroidManifest.xml" to "binary",
            ))
            val plan = PlannerDispatch(ArabifyAdapters.defaultRegistry()).plan(ref(apk), ResourceBudget())
            // NOTE: the planner sorts by confidence FIRST, then priority,
            // so the in-place adapter (higher probe confidence) outranks
            // resources (priority 100 but <=50 confidence, detection-only).
            // That matches the real MainActivity chain, which tries in-place
            // assets before ever falling to resource rewriting.
            assertEquals(
                listOf(
                    "adapter.libgdx.localeslot",
                    "adapter.gettext.po",
                    "adapter.android.assets.inplace",
                    "adapter.android.resources",
                ),
                plan.selectedAdapters.map { it.id },
            )
            dir.deleteRecursively()
            Unit
        }
    }
}
