package com.plainapps.dimmer.engine

/**
 * Where "the sun" is computed for a time zone (D-V3-04): the zone's principal location from
 * [ZoneCoordinates] (IANA zone.tab), or an estimate when the zone is not listed (UTC, Etc/GMT+n,
 * unknown IDs): longitude = standard UTC offset × 15°, latitude 45° north, [estimated] = true.
 */
data class ZoneLocation(
    val zoneId: String,
    /** The zone.tab ID the coordinates came from (differs from [zoneId] for legacy aliases). */
    val matchedId: String?,
    val latitude: Double,
    val longitude: Double,
    val estimated: Boolean,
) {
    companion object {
        const val FALLBACK_LATITUDE = 45.0

        private val table: Map<String, Pair<Double, Double>> by lazy {
            ZoneCoordinates.TABLE.lineSequence()
                .mapNotNull { line ->
                    val p = line.trim().split(' ')
                    if (p.size != 3) return@mapNotNull null
                    val lat = p[1].toDoubleOrNull() ?: return@mapNotNull null
                    val lon = p[2].toDoubleOrNull() ?: return@mapNotNull null
                    p[0] to (lat to lon)
                }
                .toMap()
        }

        /** Number of zones in the generated table (for tests). */
        internal val tableSize: Int get() = table.size

        /**
         * [canonical] maps an ID to its canonical form (on Android
         * `android.icu.util.TimeZone.getCanonicalID`; identity in JVM tests). Lookup order: the ID
         * itself, its canonical ID, then any table entry with the same canonical ID (so a legacy
         * "Asia/Calcutta" and the current "Asia/Kolkata" both find Kolkata). [standardOffsetSeconds]
         * is only used for the estimate.
         */
        fun of(
            zoneId: String,
            standardOffsetSeconds: Int,
            canonical: (String) -> String? = { it },
        ): ZoneLocation {
            table[zoneId]?.let { return ZoneLocation(zoneId, zoneId, it.first, it.second, false) }
            val canon = runCatching { canonical(zoneId) }.getOrNull()
            if (canon != null) {
                table[canon]?.let { return ZoneLocation(zoneId, canon, it.first, it.second, false) }
                val match = table.entries.firstOrNull { e ->
                    runCatching { canonical(e.key) }.getOrNull() == canon
                }
                if (match != null) {
                    return ZoneLocation(zoneId, match.key, match.value.first, match.value.second, false)
                }
            }
            val lon = (standardOffsetSeconds / 3600.0 * 15.0).coerceIn(-180.0, 180.0)
            return ZoneLocation(zoneId, null, FALLBACK_LATITUDE, lon, true)
        }
    }
}
