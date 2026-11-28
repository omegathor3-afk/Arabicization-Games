package com.omega.arabify

import org.junit.Assert.assertEquals
import org.junit.Test

class InstalledAppDiscoveryTest {
    @Test fun ignoresCandidatesWithoutPackageOrBaseApk() {
        val result = InstalledAppDiscovery.fromCandidates(
            listOf(
                LauncherAppCandidate("Missing package", "", "/data/app/base.apk"),
                LauncherAppCandidate("Missing APK", "com.example.missing", ""),
                LauncherAppCandidate("Valid", "com.example.valid", "/data/app/valid/base.apk")
            )
        )

        assertEquals(listOf("com.example.valid"), result.map { it.packageName })
    }

    @Test fun deduplicatesMultipleLauncherActivitiesAndPreservesSplitApks() {
        val splits = listOf(
            "/data/app/game/split_config.arm64_v8a.apk",
            "/data/app/game/split_config.armeabi_v7a.apk"
        )
        val result = InstalledAppDiscovery.fromCandidates(
            listOf(
                LauncherAppCandidate("Game", "com.example.game", "/data/app/game/base.apk", splits),
                LauncherAppCandidate("Game launcher alias", "com.example.game", "/data/app/game/base.apk", splits)
            )
        )

        assertEquals(1, result.size)
        assertEquals("Game", result.single().label)
        assertEquals(splits, result.single().splitApkPaths)
        assertEquals(2, result.single().splitCount)
    }

    @Test fun usesPackageAsFallbackLabelAndSortsWithoutLocaleDependence() {
        val result = InstalledAppDiscovery.fromCandidates(
            listOf(
                LauncherAppCandidate("zeta", "com.example.zeta", "/data/app/zeta/base.apk"),
                LauncherAppCandidate("", "com.example.alpha", "/data/app/alpha/base.apk")
            )
        )

        assertEquals(listOf("com.example.alpha", "com.example.zeta"), result.map { it.packageName })
        assertEquals("com.example.alpha", result.first().label)
    }
}
