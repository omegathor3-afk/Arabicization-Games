package com.omega.arabify

import java.io.File
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkLocalizationAdaptersTest {

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

    @Test
    fun localeSlotProbeMatchesBundleConvention() {
        runBlocking {
            val dir = createTempDir()
            val apk = buildApk(dir, "gdx.apk", mapOf(
                "assets/bundle.properties" to "Play=Play",
                "AndroidManifest.xml" to "binary",
            ))
            val probe = LibGdxLocaleSlotAdapter().probe(ref(apk), ResourceBudget())
            assertTrue(probe.matched)
            assertEquals("adapter.libgdx.localeslot", probe.adapterId)
            assertTrue(probe.evidence.any { it.endsWith("bundle.properties") })

            val gettextProbe = GettextPoAdapter().probe(ref(apk), ResourceBudget())
            assertFalse(gettextProbe.matched)
            dir.deleteRecursively()
        }
    }

    @Test
    fun gettextProbeRequiresPotAndLanguagesXml() {
        runBlocking {
            val dir = createTempDir()
            val both = buildApk(dir, "both.apk", mapOf(
                "assets/po/messages.pot" to "msgid \"Play\"\nmsgstr \"\"\n",
                "assets/ui/languages.xml" to "<Languages/>",
            ))
            assertTrue(GettextPoAdapter().probe(ref(both), ResourceBudget()).matched)

            val potOnly = buildApk(dir, "potonly.apk", mapOf("assets/po/messages.pot" to "msgid \"x\"\n"))
            val probe = GettextPoAdapter().probe(ref(potOnly), ResourceBudget())
            assertFalse(probe.matched)
            assertTrue(probe.blockedReason != null)
            dir.deleteRecursively()
        }
    }

    @Test
    fun resourcesProbeIsLowConfidenceDetectionOnly() {
        runBlocking {
            val dir = createTempDir()
            val apk = buildApk(dir, "arsc.apk", mapOf("resources.arsc" to "not-a-real-table"))
            val probe = AndroidResourcesAdapter().probe(ref(apk), ResourceBudget())
            assertTrue(probe.matched)
            assertTrue("arsc presence is detection, not support", probe.confidence <= 50)
            dir.deleteRecursively()
        }
    }

    @Test
    fun inPlaceProbeUsesTheRealCandidateFilter() {
        runBlocking {
            val dir = createTempDir()
            val apk = buildApk(dir, "assets.apk", mapOf(
                "assets/data/strings.json" to "{\"play\":\"Play\"}",
                "assets/icon.png" to "png-bytes",
            ))
            val probe = AndroidAssetsInPlaceAdapter().probe(ref(apk), ResourceBudget())
            assertTrue(probe.matched)
            assertTrue(probe.evidence.any { it.endsWith(".json") })
            dir.deleteRecursively()
        }
    }

    @Test
    fun emptyApkMatchesNoAdapter() {
        runBlocking {
            val dir = createTempDir()
            val apk = buildApk(dir, "empty.apk", mapOf("classes.dex" to "dex"))
            val registry = ArabifyAdapters.defaultRegistry()
            registry.all().forEach { adapter ->
                assertFalse("${adapter.descriptor.id} must not match", adapter.probe(ref(apk), ResourceBudget()).matched)
            }
            dir.deleteRecursively()
        }
    }

    @Test
    fun registryOrderPreservesTheCurrentDispatchChain() {
        val ids = ArabifyAdapters.defaultRegistry().all().map { it.descriptor.id }
        assertEquals(
            listOf(
                "adapter.libgdx.localeslot",
                "adapter.gettext.po",
                "adapter.android.resources",
                "adapter.android.assets.inplace",
            ),
            ids,
        )
    }

    @Test
    fun plannerReportsPartialBecauseWrappersDeclareNoRewriteYet() {
        runBlocking {
            val dir = createTempDir()
            val apk = buildApk(dir, "gdx.apk", mapOf("assets/bundle.properties" to "Play=Play"))
            val registry = ArabifyAdapters.defaultRegistry()
            val plan = LocalizationCapabilityPlanner(registry).plan(
                ref(apk),
                setOf(AdapterOperation.INSPECT, AdapterOperation.EXTRACT, AdapterOperation.REWRITE),
                ResourceBudget(),
            )
            // Honesty check: Phase B wrappers declare INSPECT+EXTRACT (slot
            // and gettext only) but never REWRITE, so the planner must NOT
            // report rewrite support and the status stays PARTIAL.
            assertEquals(CapabilityStatus.PARTIAL, plan.status)
            assertTrue(plan.missingOperations.contains(AdapterOperation.REWRITE))
            assertEquals("adapter.libgdx.localeslot", plan.selectedAdapters.first().id)
            dir.deleteRecursively()
        }
    }

    @Test
    fun plannerHonorsInputBudgetBeforeProbing() {
        runBlocking {
            val dir = createTempDir()
            val apk = buildApk(dir, "gdx.apk", mapOf("assets/bundle.properties" to "Play=Play"))
            val tight = ResourceBudget(maxInputBytes = 4)
            val failed = runCatching {
                LocalizationCapabilityPlanner(ArabifyAdapters.defaultRegistry()).plan(
                    ref(apk), setOf(AdapterOperation.INSPECT), tight,
                )
            }
            assertTrue(failed.isFailure)
            dir.deleteRecursively()
        }
    }
    private class ThrowingProbeAdapter(private val failure: Throwable) : ApkProbeAdapter() {
        override val descriptor = AdapterDescriptor(
            id = "adapter.test.throwing",
            version = "1",
            displayName = "Throwing test adapter",
            inputKinds = setOf(ArtifactKind.APK),
            outputKinds = emptySet(),
            operations = emptySet(),
        )

        override suspend fun probeApk(input: ArtifactRef, budget: ResourceBudget): AdapterProbe {
            throw failure
        }
    }

    @Test
    fun probePropagatesCancellationAndFatalErrors() = runBlocking {
        val dir = createTempDir()
        val apk = buildApk(dir, "probe.apk", mapOf("classes.dex" to "dex"))
        val cancellation = CancellationException("cancel this probe")
        val cancelled = runCatching {
            ThrowingProbeAdapter(cancellation).probe(ref(apk), ResourceBudget())
        }
        assertSame(cancellation, cancelled.exceptionOrNull())

        val fatal = AssertionError("fatal probe failure")
        val failed = runCatching {
            ThrowingProbeAdapter(fatal).probe(ref(apk), ResourceBudget())
        }
        assertSame(fatal, failed.exceptionOrNull())
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun probesHonorCallerEntryBudgetAndPlannerReportsBlocked() = runBlocking {
        val dir = createTempDir()
        val apk = buildApk(dir, "many-entries.apk", mapOf(
            "assets/bundle.properties" to "Play=Play",
            "assets/one.txt" to "one",
            "assets/two.txt" to "two",
        ))
        val adapter = LibGdxLocaleSlotAdapter()
        val budget = ResourceBudget(maxEntries = 2)
        val probe = adapter.probe(ref(apk), budget)
        assertFalse(probe.matched)
        assertTrue(probe.blockedReason?.contains("entry count") == true)

        val plan = LocalizationCapabilityPlanner(
            LocalizationAdapterRegistry(listOf(adapter))
        ).plan(ref(apk), setOf(AdapterOperation.INSPECT), budget)
        assertEquals(CapabilityStatus.BLOCKED, plan.status)
        assertTrue(plan.explanations.any { it.contains("blocked") })
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun probeOnlyReportsNeverClaimRealGameEmulatorEvidence() = runBlocking {
        val dir = createTempDir()
        val apk = buildApk(dir, "all-markers.apk", mapOf(
            "assets/bundle.properties" to "Play=Play",
            "assets/po/messages.pot" to "msgid \"Play\"\\nmsgstr \"\"\\n",
            "assets/ui/languages.xml" to "<Languages/>",
            "resources.arsc" to "fixture",
            "assets/data/strings.json" to "{\"play\":\"Play\"}",
        ))
        val reports = listOf(
            LibGdxLocaleSlotAdapter(),
            GettextPoAdapter(),
            AndroidResourcesAdapter(),
            AndroidAssetsInPlaceAdapter(),
        ).map { it.inspect(ref(apk), ResourceBudget()) }
        assertTrue(reports.all { it.evidenceLevel == "UNIT_FIXTURE" })
        dir.deleteRecursively()
        Unit
    }

}
