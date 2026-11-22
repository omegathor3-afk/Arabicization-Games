package com.omega.arabify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnityTextAssetSelectionTest {
    private fun objectInfo(pathId: Long, classId: Int): UnitySerializedObjectInfo =
        UnitySerializedObjectInfo(
            pathId = pathId,
            byteStart = 0L,
            absoluteDataOffset = 0L,
            byteSize = 16L,
            typeId = 0,
            classId = classId,
            destroyed = null,
            scriptTypeIndex = null,
            stripped = null
        )

    @Test
    fun preservesOrderAndReportsWhenAnAdditionalTextAssetIsOmitted() {
        val objects = listOf(
            objectInfo(1L, 49),
            objectInfo(2L, 28),
            objectInfo(3L, 49),
            objectInfo(4L, 49),
            objectInfo(5L, 49)
        )
        var inspected = 0

        val selection = selectUnityTextAssetObjects(objects, maxObjects = 2) {
            inspected++
            it.classId == 49
        }

        assertEquals(listOf(1L, 3L), selection.objects.map { it.pathId })
        assertTrue(selection.limitReached)
        // Stop at the first extra match; do not scan the rest of a potentially
        // very large object table merely to calculate an exact omitted count.
        assertEquals(4, inspected)
    }

    @Test
    fun doesNotReportLimitWhenCandidateCountExactlyMatchesLimit() {
        val objects = listOf(objectInfo(1L, 49), objectInfo(2L, 28), objectInfo(3L, 49))

        val selection = selectUnityTextAssetObjects(objects, maxObjects = 2) {
            it.classId == 49
        }

        assertEquals(listOf(1L, 3L), selection.objects.map { it.pathId })
        assertFalse(selection.limitReached)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnboundedCandidateLimits() {
        selectUnityTextAssetObjects(emptyList(), maxObjects = 1_001) { true }
    }
}
