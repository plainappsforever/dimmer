package com.plainapps.dimmer.engine

/**
 * v3.1.1 (Play audit C2): the privacy policy link in Settings > About. Play's User Data policy wants the link inside
 * the app as well as in the Console. Opened in the user's browser with ACTION_VIEW, so Dimmer still needs no INTERNET
 * permission. Pure and JVM-tested: [open] gets the platform call as a function.
 */
object PrivacyLink {
    const val URL = "https://plainapps.dfdsfdsfsdf.workers.dev/apps/dimmer/privacy"

    /**
     * Calls [launch] with [URL]. Returns false when it throws (ActivityNotFoundException when no browser is installed,
     * SecurityException from an odd handler): the caller then shows the address as text instead.
     */
    fun open(launch: (String) -> Unit): Boolean =
        try {
            launch(URL)
            true
        } catch (e: RuntimeException) {
            false
        }
}
