package com.andrej.glasstv.data

/**
 * Apps whose Top Shelf is another app's. TizenTube is YouTube for the TV (a YouTube client without
 * ads): it keeps its own tile, and its Top Shelf is YouTube's.
 */
private val SHELF_ALIASES = mapOf(
    "io.gh.reisxd.tizentube.cobalt" to "com.google.android.youtube.tv",
)

/** The package whose designed tile an app uses. The emulator preview's stand-ins are "preview.<package>". */
fun tileName(packageName: String): String = packageName.removePrefix("preview.")

/** The package whose designed Top Shelf an app uses. */
fun shelfName(packageName: String): String = tileName(packageName).let { SHELF_ALIASES[it] ?: it }
