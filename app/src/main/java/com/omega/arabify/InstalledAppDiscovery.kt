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
