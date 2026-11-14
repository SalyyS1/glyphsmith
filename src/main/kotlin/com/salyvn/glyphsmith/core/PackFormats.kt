package com.salyvn.glyphsmith.core

import com.salyvn.glyphsmith.pack.PackFormat

/**
 * Minecraft version names mapped to resource pack formats, so a spec can say `1.21.4..26.2` instead of
 * `46..88`.
 *
 * The table lists releases only, and only from format 15 up, because 15 is the lowest a multi-version
 * pack may declare. It was derived from the version summary that `misode/mcmeta` publishes, which itself
 * mirrors Mojang's version manifest; the numbers are facts about the game and are stated here rather than
 * fetched so the tool works offline. Snapshots and pre-releases move too fast to hard-code — pass their
 * format number directly.
 */
object PackFormats {
    private val RELEASES: Map<String, Int> = linkedMapOf(
        "1.20" to 15,
        "1.20.1" to 15,
        "1.20.2" to 18,
        "1.20.3" to 22,
        "1.20.4" to 22,
        "1.20.5" to 32,
        "1.20.6" to 32,
        "1.21" to 34,
        "1.21.1" to 34,
        "1.21.2" to 42,
        "1.21.3" to 42,
        "1.21.4" to 46,
        "1.21.5" to 55,
        "1.21.6" to 63,
        "1.21.7" to 64,
        "1.21.8" to 64,
        "1.21.9" to 69,
        "1.21.10" to 69,
        "1.21.11" to 75,
        "26.1" to 84,
        "26.1.1" to 84,
        "26.1.2" to 84,
        "26.2" to 88,
        "26.3" to 97,
    )

    val newestRelease: String = RELEASES.keys.last()

    fun formatOf(version: String): Int = RELEASES[version]
        ?: throw IllegalArgumentException(
            "Unknown Minecraft version '$version'. Known releases: ${RELEASES.keys.joinToString(", ")}. " +
                "For a snapshot, write its resource pack format number instead.",
        )

    /**
     * Parses `1.21.4..26.2`, `26.2`, `46..88`, `88` or `97.1` into the two ends of a range. A bare major
     * is left without a minor on purpose: the client reads a missing minor as 0 in `min_format` and as
     * "any" in `max_format`, which is what a pack normally wants.
     */
    fun parseRange(text: String): Pair<PackFormat, PackFormat> {
        val parts = text.split("..")
        if (parts.size > 2) throw IllegalArgumentException("'$text' is not a version range; write '1.21.4..26.2'")
        val min = parseOne(parts[0].trim())
        val max = if (parts.size == 2) parseOne(parts[1].trim()) else min
        return min to max
    }

    private fun parseOne(text: String): PackFormat {
        if (text.isEmpty()) throw IllegalArgumentException("A version range cannot have an empty end")
        RELEASES[text]?.let { return PackFormat(it) }
        // A number is a pack format, not a version: `88` and `97.1` are formats, `1.21.4` is a version.
        val numeric = text.split(".")
        if (numeric.size <= 2 && numeric.all { part -> part.toIntOrNull() != null }) {
            val major = numeric[0].toInt()
            val minor = numeric.getOrNull(1)?.toInt()
            if (numeric.size == 1 || major >= 65) return PackFormat(major, minor)
        }
        return PackFormat(formatOf(text))
    }
}
