package com.plainapps.dimmer

import android.content.ActivityNotFoundException
import com.plainapps.dimmer.engine.InstallSource
import com.plainapps.dimmer.engine.PrivacyLink
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/** v3.1.1 Play audit fixes: the restricted-settings sentence (C3) and the in-app privacy link (C2). */
class PlayAuditTest {

    @Test
    fun `restricted-settings sentence hidden for Google Play installs`() {
        assertFalse(InstallSource.showRestrictedSettingsHint(36, "com.android.vending"))
        assertFalse(InstallSource.showRestrictedSettingsHint(33, "com.android.vending"))
    }

    @Test
    fun `restricted-settings sentence shown for sideloads and unknown installers on Android 13+`() {
        assertTrue(InstallSource.showRestrictedSettingsHint(36, null)) // adb, or the lookup failed
        assertTrue(InstallSource.showRestrictedSettingsHint(33, "com.google.android.packageinstaller"))
        assertTrue(InstallSource.showRestrictedSettingsHint(36, "org.fdroid.fdroid"))
        assertTrue(InstallSource.showRestrictedSettingsHint(36, "com.android.vending.fake"))
    }

    @Test
    fun `restricted-settings sentence never shown before Android 13 (the setting does not exist)`() {
        assertFalse(InstallSource.showRestrictedSettingsHint(32, null))
        assertFalse(InstallSource.showRestrictedSettingsHint(26, "com.google.android.packageinstaller"))
    }

    @Test
    fun `privacy link opens the https policy page`() {
        val opened = mutableListOf<String>()
        assertTrue(PrivacyLink.open { opened += it })
        assertEquals(listOf("https://taulon.org/apps/dimmer/privacy"), opened)
        assertTrue(PrivacyLink.URL.startsWith("https://"))
    }

    @Test
    fun `privacy link without a browser falls back to showing the address`() {
        assertFalse(PrivacyLink.open { throw ActivityNotFoundException("no browser") })
        assertFalse(PrivacyLink.open { throw SecurityException("refused") })
    }
}
