package com.salyvn.glyphsmith.pack

/** A pack format, which since resource pack format 65 has a minor component as well as a major one. */
data class PackFormat(val major: Int, val minor: Int? = null) {
    init {
        require(major >= 0) { "A pack format cannot be negative" }
        require(minor == null || minor >= 0) { "A pack format's minor version cannot be negative" }
    }

    /** `15` or `[97, 1]` - the compact form the client's codec accepts for both spellings. */
    fun toJson(): Json = if (minor == null) Json.Num(major) else Json.Arr(listOf(Json.Num(major), Json.Num(minor)))

    override fun toString(): String = if (minor == null) "$major" else "$major.$minor"

    companion object {
        fun exceedsRange(min: PackFormat, max: PackFormat): Boolean {
            if (min.major != max.major) return min.major > max.major
            val minMinor = min.minor ?: 0
            val maxMinor = max.minor ?: Int.MAX_VALUE
            return minMinor > maxMinor
        }
    }
}

/**
 * One `overlays.entries[]` layer in `pack.mcmeta`, compiled to the era rules matching `[min..max]`.
 */
data class OverlaySpec(
    val directory: String,
    val min: PackFormat,
    val max: PackFormat,
) {
    init {
        require(OVERLAY_DIR_REGEX.matches(directory)) {
            "Overlay directory '$directory' must match [a-z0-9_-]+"
        }
        if (PackFormat.exceedsRange(min, max)) {
            throw PackMetaException("Overlay '$directory' min_format ($min) is greater than max_format ($max)")
        }
    }

    fun toJson(): Json.Obj {
        val fields = mutableListOf<Pair<String, Json>>("directory" to Json.Text(directory))
        if (min.major > PackMeta.LAST_PRE_MINOR_FORMAT) {
            fields += "min_format" to min.toJson()
            fields += "max_format" to max.toJson()
        } else {
            val legacyCeiling = minOf(max.major, PackMeta.LAST_PRE_MINOR_FORMAT)
            val legacyFormats = if (min.major == legacyCeiling) {
                Json.Num(min.major)
            } else {
                Json.Arr(listOf(Json.Num(min.major), Json.Num(legacyCeiling)))
            }
            fields += "formats" to legacyFormats
            fields += "min_format" to min.toJson()
            fields += "max_format" to max.toJson()
        }
        return Json.Obj(fields)
    }

    companion object {
        val OVERLAY_DIR_REGEX = Regex("^[a-z0-9_-]+$")
    }
}

/** Raised when a requested version range cannot be expressed in a `pack.mcmeta` the client will accept. */
class PackMetaException(message: String) : Exception(message)

/**
 * `pack.mcmeta`, built to the rules the client actually enforces through Minecraft 26.3 (format 97).
 *
 * Resource pack format 64 (1.21.8) is the last one without minor versions. From 65 up (through 26.3 format
 * 97), `min_format` and `max_format` are mandatory and `supported_formats` is rejected when `min_format` is
 * 65 or higher. Below 65, a dual-era range spanning `1.20.1..26.3` (`15..97`) must carry `pack_format`,
 * `supported_formats: [min, 64]`, `min_format`, and `max_format` simultaneously.
 */
object PackMeta {
    /** `PackFormat.lastPreMinorVersion(CLIENT_RESOURCES)`. */
    const val LAST_PRE_MINOR_FORMAT: Int = 64

    /** `validatePackFormatForRange` refuses a multi-version pack whose main format is below this. */
    const val MULTI_VERSION_FLOOR: Int = 15

    fun build(
        description: String,
        min: PackFormat,
        max: PackFormat,
        overlays: List<OverlaySpec> = emptyList(),
    ): Json.Obj {
        if (PackFormat.exceedsRange(min, max)) {
            throw PackMetaException("min_format ($min) is greater than max_format ($max)")
        }
        val pack = mutableListOf<Pair<String, Json>>("description" to Json.Text(description))
        if (min.major > LAST_PRE_MINOR_FORMAT) {
            pack += "min_format" to min.toJson()
            pack += "max_format" to max.toJson()
        } else {
            if (min.major != max.major && min.major < MULTI_VERSION_FLOOR) {
                throw PackMetaException(
                    "A pack that also targets format ${max.major} cannot start below $MULTI_VERSION_FLOOR " +
                        "(1.20): the client refuses it because versions in between would be unable to load it.",
                )
            } else if (min.major < MULTI_VERSION_FLOOR) {
                throw PackMetaException(
                    "A pack that also targets format ${max.major} cannot start below $MULTI_VERSION_FLOOR " +
                        "(1.20): the client refuses it because versions in between would be unable to load it.",
                )
            }
            val legacyCeiling = minOf(max.major, LAST_PRE_MINOR_FORMAT)
            pack += "pack_format" to Json.Num(min.major)
            pack += "supported_formats" to Json.Arr(listOf(Json.Num(min.major), Json.Num(legacyCeiling)))
            pack += "min_format" to min.toJson()
            pack += "max_format" to max.toJson()
        }

        val rootEntries = mutableListOf<Pair<String, Json>>("pack" to Json.Obj(pack))
        if (overlays.isNotEmpty()) {
            val seenDirs = HashSet<String>()
            for (ov in overlays) {
                if (!seenDirs.add(ov.directory)) {
                    throw PackMetaException("Duplicate overlay directory '${ov.directory}' in pack.mcmeta")
                }
            }
            rootEntries += "overlays" to Json.Obj(
                "entries" to Json.Arr(overlays.map(OverlaySpec::toJson)),
            )
        }
        return Json.Obj(rootEntries)
    }

    fun render(
        description: String,
        min: PackFormat,
        max: PackFormat,
        overlays: List<OverlaySpec> = emptyList(),
    ): String = build(description, min, max, overlays).render() + "\n"
}
