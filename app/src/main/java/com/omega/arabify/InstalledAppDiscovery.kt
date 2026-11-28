package com.omega.arabify

import java.util.Locale

internal data class InstalledApp(
    val label: String,
    val packageName: String,
    val apkPath: String,
    val splitApkPaths: List<String>
) {
    val splitCount: Int get() = splitApkPaths.size
}

internal data class LauncherAppCandidate(
    val label: String,
    val packageName: String,
    val apkPath: String,
    val splitApkPaths: List<String> = emptyList()
)

/**
 * Converts launcher-query results into a stable, package-deduplicated app list.
 * Package visibility is intentionally limited to apps that expose a launcher activity.
 */
internal object InstalledAppDiscovery {
    fun fromCandidates(candidates: Iterable<LauncherAppCandidate>): List<InstalledApp> {
        val uniqueByPackage = LinkedHashMap<String, InstalledApp>()
        for (candidate in candidates) {
            if (candidate.packageName.isBlank() || candidate.apkPath.isBlank()) continue
            if (candidate.packageName in uniqueByPackage) continue
            uniqueByPackage[candidate.packageName] = InstalledApp(
                label = candidate.label.ifBlank { candidate.packageName },
                packageName = candidate.packageName,
                apkPath = candidate.apkPath,
                splitApkPaths = candidate.splitApkPaths.filter { it.isNotBlank() }.distinct()
            )
        }
        return uniqueByPackage.values.sortedBy { it.label.lowercase(Locale.ROOT) }
    }
}


internal enum class InstalledAppsEmptyReason {
    NO_LAUNCHER_APPS,
    NO_SEARCH_MATCHES
}

internal sealed interface InstalledAppsContentState {
    data object Loading : InstalledAppsContentState
    data class Error(val message: String) : InstalledAppsContentState
    data class Empty(val reason: InstalledAppsEmptyReason) : InstalledAppsContentState
    data class Results(val count: Int) : InstalledAppsContentState
}

/**
 * Keeps an unfinished launcher query from being presented as a successful empty result.
 * Error and loading states take precedence over the current (possibly empty) app list.
 */
internal fun installedAppsContentState(
    isLoading: Boolean,
    error: String?,
    totalCount: Int,
    visibleCount: Int
): InstalledAppsContentState {
    if (isLoading) return InstalledAppsContentState.Loading
    error?.takeIf { it.isNotBlank() }?.let { return InstalledAppsContentState.Error(it) }

    val total = totalCount.coerceAtLeast(0)
    val visible = visibleCount.coerceIn(0, total)
    if (visible == 0) {
        return InstalledAppsContentState.Empty(
            if (total == 0) InstalledAppsEmptyReason.NO_LAUNCHER_APPS
            else InstalledAppsEmptyReason.NO_SEARCH_MATCHES
        )
    }
    return InstalledAppsContentState.Results(visible)
}
