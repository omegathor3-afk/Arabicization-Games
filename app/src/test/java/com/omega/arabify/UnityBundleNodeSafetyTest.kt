package com.omega.arabify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnityBundleNodeSafetyTest {
    @Test fun normalizesSafeRelativePathsAndRejectsTraversalOrAbsolutePaths() {
        assertEquals("CAB-1/assets/shared.assets", UnityBundleNodeSafety.safeRelativePath("CAB-1\\\\assets/shared.assets"))
        listOf(
            "../outside", "CAB/../../outside", "/absolute/path", "\\\\server\\share",
            "C:\\temp\\file", "file://host/path", "a//b", "a/./b", "a/../b",
            "bad:name", "trailing.", "trailing "
        ).forEach { assertNull("Should reject: $it", UnityBundleNodeSafety.safeRelativePath(it)) }
    }

    @Test fun acceptsAdjacentAndZeroLengthRanges() {
        val nodes = listOf(
            UnityBundleNode(0, 4, 0, "CAB/a"),
            UnityBundleNode(4, 0, 0, "CAB/empty"),
            UnityBundleNode(4, 6, 0, "CAB/b")
        )
        assertTrue(UnityBundleNodeSafety.validateNodeLayout(nodes, 10))
    }

    @Test fun rejectsOverlappingOutOfBoundsAndDuplicatePaths() {
        assertFalse(UnityBundleNodeSafety.validateNodeLayout(
            listOf(UnityBundleNode(0, 5, 0, "CAB/a"), UnityBundleNode(4, 2, 0, "CAB/b")), 10
        ))
        assertFalse(UnityBundleNodeSafety.validateNodeLayout(
            listOf(UnityBundleNode(9, 2, 0, "CAB/a")), 10
        ))
        assertFalse(UnityBundleNodeSafety.validateNodeLayout(
            listOf(UnityBundleNode(0, 1, 0, "CAB/A"), UnityBundleNode(1, 1, 0, "cab/a")), 2
        ))
    }

    @Test fun rejectsOverflowAndInvalidTotals() {
        assertFalse(UnityBundleNodeSafety.validateNodeLayout(
            listOf(UnityBundleNode(Long.MAX_VALUE, 2, 0, "CAB/a")), Long.MAX_VALUE
        ))
        assertFalse(UnityBundleNodeSafety.validateNodeLayout(emptyList(), -1))
        assertTrue(UnityBundleNodeSafety.validateNodeLayout(emptyList(), 0))
    }
}
