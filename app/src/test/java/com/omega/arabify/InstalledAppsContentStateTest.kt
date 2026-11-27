package com.omega.arabify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstalledAppsContentStateTest {
    @Test fun loadingIsNotReportedAsAnEmptyInstalledAppList() {
        assertEquals(
            InstalledAppsContentState.Loading,
            installedAppsContentState(
                isLoading = true,
                error = null,
                totalCount = 0,
                visibleCount = 0
            )
        )
    }

    @Test fun loadingTakesPrecedenceOverAStaleErrorOrCachedResults() {
        assertEquals(
            InstalledAppsContentState.Loading,
            installedAppsContentState(
                isLoading = true,
                error = "previous query failed",
                totalCount = 12,
                visibleCount = 4
            )
        )
    }

    @Test fun failedDiscoveryIsNotReportedAsNoInstalledApps() {
        assertEquals(
            InstalledAppsContentState.Error("PackageManager unavailable"),
            installedAppsContentState(
                isLoading = false,
                error = "PackageManager unavailable",
                totalCount = 0,
                visibleCount = 0
            )
        )
    }

    @Test fun completedEmptyDiscoveryReportsNoLauncherApps() {
        assertEquals(
            InstalledAppsContentState.Empty(InstalledAppsEmptyReason.NO_LAUNCHER_APPS),
            installedAppsContentState(
                isLoading = false,
                error = null,
                totalCount = 0,
                visibleCount = 0
            )
        )
    }

    @Test fun anEmptySearchResultIsDifferentFromAnEmptyDeviceList() {
        assertEquals(
            InstalledAppsContentState.Empty(InstalledAppsEmptyReason.NO_SEARCH_MATCHES),
            installedAppsContentState(
                isLoading = false,
                error = null,
                totalCount = 19,
                visibleCount = 0
            )
        )
    }

    @Test fun resultsUseTheFilteredCountAndClampInvalidCounts() {
        assertEquals(
            InstalledAppsContentState.Results(3),
            installedAppsContentState(
                isLoading = false,
                error = null,
                totalCount = 19,
                visibleCount = 3
            )
        )
        assertTrue(
            installedAppsContentState(
                isLoading = false,
                error = null,
                totalCount = 2,
                visibleCount = 9
            ) == InstalledAppsContentState.Results(2)
        )
    }
}
