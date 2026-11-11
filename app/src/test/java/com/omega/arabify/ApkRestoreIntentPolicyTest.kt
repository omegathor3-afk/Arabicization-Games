package com.omega.arabify

import android.content.pm.ApplicationInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkRestoreIntentPolicyTest {
    @Test
    fun acceptsOnlyNamedSystemInstallerPackages() {
        assertTrue(
            isTrustedInstallerConfirmation(
                "com.android.packageinstaller",
                ApplicationInfo.FLAG_SYSTEM
            )
        )
        assertTrue(
            isTrustedInstallerConfirmation(
                "com.oem.packageinstaller",
                ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
            )
        )
    }

    @Test
    fun rejectsMissingOrNonSystemInstallerPackages() {
        assertFalse(isTrustedInstallerConfirmation(null, ApplicationInfo.FLAG_SYSTEM))
        assertFalse(isTrustedInstallerConfirmation("", ApplicationInfo.FLAG_SYSTEM))
        assertFalse(isTrustedInstallerConfirmation("com.example.app", 0))
    }
}
