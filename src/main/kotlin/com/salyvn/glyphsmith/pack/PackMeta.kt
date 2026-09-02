package com.salyvn.glyphsmith.pack

/** A pack format, which since resource pack format 65 has a minor component as well as a major one. */
data class PackFormat(val major: Int, val minor: Int? = null) {
    init {
        require(major >= 0) { "A pack format cannot be negative" }
        require(minor == null || minor >= 0) { "A pack format's minor version cannot be negative" }
    }

    /** `15` or `[97, 1]` — the compact form the client's codec accepts for both spellings. */
    fun toJson(): Json = if (minor == null) Json.Num(major) else Json.Arr(listOf(Json.Num(major), Json.Num(minor)))

    override fun toString(): String = if (minor == null) "$major" else "$major.$minor"
}

/** Raised when a requested version range cannot be expressed in a `pack.mcmeta` the client will accept. */
class PackMetaException(message: String) : Exception(message)

/**
 * `pack.mcmeta`, built to the rules the client actually enforces.
 *
 * Those rules changed and are unforgiving, so they are worth stating. Resource pack format 64 — 1.21.8 —
 * is the last one without minor versions. From 65 up, `min_format` and `max_format` are mandatory and
 * must both be present; `supported_formats` is *rejected* there, with the client telling you to remove
 * it. Below 65 the opposite holds: a range that reaches down there has to carry `supported_formats` and
 * `pack_format` as well, `supported_formats` has to start on exactly the same major as `min_format` and
 * end either on `max_format`'s major or on 64, and `pack_format` has to sit inside the range and be at
 * least 15. A pack that only says `pack_format` cannot claim anything above 64 at all.
 *
 * Getting one of those wrong does not degrade gracefully: the pack is listed as incompatible or refuses
 * to load. Writing the file by hand for a range like 1.20.1 to 26.2 means satisfying four fields that
 * validate against each other, which is the sort of thing a generator should do once and correctly.
 */
object PackMeta {
    /** `PackFormat.lastPreMinorVersion(CLIENT_RESOURCES)`. */
    const val LAST_PRE_MINOR_FORMAT: Int = 64

    /** `validatePackFormatForRange` refuses a multi-version pack whose main format is below this. */
    const val MULTI_VERSION_FLOOR: Int = 15

    fun build(description: String, min: PackFormat, max: PackFormat): Json.Obj {
        if (min.major > max.major) {
            throw PackMetaException("min_format ($min) is greater than max_format ($max)")
        }
        val pack = mutableListOf<Pair<String, Json>>("description" to Json.Text(description))
        if (min.major > LAST_PRE_MINOR_FORMAT) {
            // Modern only. `pack_format` and `supported_formats` are not just unnecessary here, the
            // second one is an error the client spells out.
            pack += "min_format" to min.toJson()
            pack += "max_format" to max.toJson()
        } else {
            if (min.major < MULTI_VERSION_FLOOR) {
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
        return Json.Obj("pack" to Json.Obj(pack))
    }

    fun render(description: String, min: PackFormat, max: PackFormat): String =
        build(description, min, max).render() + "\n"
}
