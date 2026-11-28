package com.omega.arabify

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LocalizationCapabilityPlannerTest {
    private val artifact = ArtifactRef("f", "fixture", "content://fixture/game.apk", ArtifactKind.APK, 100L)
    private class Fake(
        override val descriptor: AdapterDescriptor,
        private val result: AdapterProbe
    ) : LocalizationAdapter {
        override suspend fun probe(input: ArtifactRef, budget: ResourceBudget) = result
    }

    @Test fun composesContainerAndTextAdapters() = runBlocking {
        val a = Fake(
            AdapterDescriptor("zip", "1", "ZIP", setOf(ArtifactKind.APK), setOf(ArtifactKind.APK),
                setOf(AdapterOperation.INSPECT, AdapterOperation.EXTRACT)),
            AdapterProbe("zip", true, 90)
        )
        val b = Fake(
            AdapterDescriptor("json", "1", "JSON", setOf(ArtifactKind.APK), setOf(ArtifactKind.APK),
                setOf(AdapterOperation.REWRITE, AdapterOperation.VERIFY)),
            AdapterProbe("json", true, 80)
        )
        val plan = LocalizationCapabilityPlanner(LocalizationAdapterRegistry(listOf(a, b))).plan(
            artifact, AdapterOperation.values().toSet(), ResourceBudget()
        )
        assertEquals(CapabilityStatus.SUPPORTED, plan.status)
        assertEquals(4, plan.coveredOperations.size)
        assertEquals(2, plan.selectedAdapters.size)
    }

    @Test fun fingerprintOnlyIsNotRewriteSupport() = runBlocking {
        val detector = Fake(
            AdapterDescriptor("fingerprint", "1", "Fingerprint", setOf(ArtifactKind.APK), emptySet(),
                setOf(AdapterOperation.INSPECT)),
            AdapterProbe("fingerprint", true, 99)
        )
        val plan = LocalizationCapabilityPlanner(LocalizationAdapterRegistry(listOf(detector))).plan(
            artifact, setOf(AdapterOperation.INSPECT, AdapterOperation.EXTRACT, AdapterOperation.REWRITE), ResourceBudget()
        )
        assertEquals(CapabilityStatus.INSPECT_ONLY, plan.status)
        assertFalse(AdapterOperation.REWRITE in plan.coveredOperations)
    }

    @Test fun inputBudgetCheckedBeforeProbe() = runBlocking {
        val detector = Fake(
            AdapterDescriptor("probe", "1", "Probe", setOf(ArtifactKind.APK), emptySet(),
                setOf(AdapterOperation.INSPECT)),
            AdapterProbe("probe", true, 100)
        )
        try {
            LocalizationCapabilityPlanner(LocalizationAdapterRegistry(listOf(detector))).plan(
                artifact.copy(sizeBytes = 2048L), setOf(AdapterOperation.INSPECT), ResourceBudget(maxInputBytes = 1024L)
            )
            fail("Expected budget rejection")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun duplicateAdapterIdsRejected() {
        val d = AdapterDescriptor("same", "1", "Same", setOf(ArtifactKind.RAW_FILE), emptySet(),
            setOf(AdapterOperation.INSPECT))
        val a = Fake(d, AdapterProbe("same", true, 10))
        val b = Fake(d, AdapterProbe("same", true, 20))
        try {
            LocalizationAdapterRegistry(listOf(a, b))
            fail("Expected duplicate ID rejection")
        } catch (_: IllegalArgumentException) { }
    }
}
