package com.omega.arabify

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAssetLocalizerPathTest {
    @Test fun unityStreamingAssetsScopeIncludesOnlySupportedTextFiles() {
        val root = listOf("assets/bin/Data/StreamingAssets/")
        assertTrue(AndroidAssetLocalizer.isSupportedTextAsset(
            "assets/bin/Data/StreamingAssets/localization/en.json", 120, root
        ))
        assertTrue(AndroidAssetLocalizer.isSupportedTextAsset(
            "assets/bin/Data/StreamingAssets/dialogue.txt", 80, root
        ))
        assertFalse(AndroidAssetLocalizer.isSupportedTextAsset(
            "assets/bin/Data/Resources/config.json", 120, root
        ))
        assertFalse(AndroidAssetLocalizer.isSupportedTextAsset(
            "assets/bin/Data/StreamingAssets/texture.png", 120, root
        ))
        assertFalse(AndroidAssetLocalizer.isSupportedTextAsset(
            "assets/bin/Data/StreamingAssets/empty.txt", 0, root
        ))
    }

    @Test fun defaultAndroidRootsRemainSupported() {
        assertTrue(AndroidAssetLocalizer.isSupportedTextAsset("assets/localization/en.json", 64))
        assertTrue(AndroidAssetLocalizer.isSupportedTextAsset("res/raw/dialogue.txt", 64))
        assertFalse(AndroidAssetLocalizer.isSupportedTextAsset("lib/arm64-v8a/libgame.so", 64))
    }
}
