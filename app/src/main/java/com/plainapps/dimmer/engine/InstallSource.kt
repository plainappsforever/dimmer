package com.plainapps.dimmer.engine

/**
 * v3.1.1 (Play audit C3): whether the disclosure shows the "Allow restricted settings" sentence. Android 13+
 * restricts accessibility access for apps installed outside an app store; Google Play installs are not affected,
 * so a Play user (and the Play reviewer) never sees instructions about a security control they do not meet.
 * Pure and JVM-tested; the installer name comes from `PackageManager.getInstallSourceInfo` (API 30+).
 */
object InstallSource {
    const val GOOGLE_PLAY = "com.android.vending"

    /** Android 13 (API 33) introduced restricted settings. */
    const val RESTRICTED_SETTINGS_SINCE_SDK = 33

    /**
     * @param sdkInt `Build.VERSION.SDK_INT`.
     * @param installer the installing package, or null when unknown (adb, a file manager, an error).
     */
    fun showRestrictedSettingsHint(sdkInt: Int, installer: String?): Boolean =
        sdkInt >= RESTRICTED_SETTINGS_SINCE_SDK && installer != GOOGLE_PLAY
}
