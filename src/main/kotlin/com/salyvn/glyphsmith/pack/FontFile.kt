package com.salyvn.glyphsmith.pack

import com.salyvn.glyphsmith.codepoint.Codepoints
import com.salyvn.glyphsmith.glyph.GlyphMath

/**
 * Vanilla `GlyphProviderConditional` filter options (`"filter": { "uniform": Boolean, "jp": Boolean }`).
 * Only `uniform` and `jp` are accepted by `FontOption.CODEC` through Minecraft 26.3 (format 97).
 */
data class ProviderFilter(
    val uniform: Boolean? = null,
    val jp: Boolean? = null,
) {
    val isEmpty: Boolean get() = uniform == null && jp == null

    fun toJson(): Json.Obj {
        val entries = mutableListOf<Pair<String, Json>>()
        uniform?.let { entries += "uniform" to Json.Bool(it) }
        jp?.let { entries += "jp" to Json.Bool(it) }
        return Json.Obj(entries)
    }
}

/** Optional `size_overrides` entry on a `unihex` font provider. */
data class UnihexSizeOverride(
    val from: String,
    val to: String,
    val left: Int,
    val right: Int,
) {
    init {
        require(from.codePointCount(0, from.length) == 1) { "unihex size_override 'from' must be 1 codepoint" }
        require(to.codePointCount(0, to.length) == 1) { "unihex size_override 'to' must be 1 codepoint" }
    }

    fun toJson(): Json = Json.Obj(
        "from" to Json.Text(from),
        "to" to Json.Text(to),
        "left" to Json.Num(left),
        "right" to Json.Num(right),
    )
}

/** One entry in a font file's `providers` array. */
sealed class Provider {
    abstract val filter: ProviderFilter?
    abstract fun toJson(): Json

    protected fun MutableList<Pair<String, Json>>.appendFilterIfPresent(filter: ProviderFilter?) {
        if (filter != null && !filter.isEmpty) {
            add("filter" to filter.toJson())
        }
    }

    /**
     * A `bitmap` provider. [chars] is the codepoint grid: one string per row, every row the same length,
     * padded with `\u0000` where a slot is empty, because unequal rows are rejected with
     * "Lines in codepoint grid have to be the same length".
     */
    data class Bitmap(
        val file: String,
        val height: Int,
        val ascent: Int,
        val chars: List<String>,
        override val filter: ProviderFilter? = null,
    ) : Provider() {
        init {
            require(chars.isNotEmpty()) { "A bitmap provider needs at least one row of codepoints" }
            val width = chars.first().codePointCount(0, chars.first().length)
            chars.forEach { row ->
                require(row.codePointCount(0, row.length) == width) {
                    "Every row of a codepoint grid must hold the same number of codepoints"
                }
            }
            require(ascent <= height) { "Ascent $ascent is higher than height $height" }
        }

        override fun toJson(): Json = Json.Obj(
            buildList {
                add("type" to Json.Text("bitmap"))
                add("file" to Json.Text(file))
                add("height" to Json.Num(height))
                add("ascent" to Json.Num(ascent))
                add("chars" to Json.Arr(chars.map { row -> Json.Text(row) }))
                appendFilterIfPresent(filter)
            },
        )
    }

    /**
     * A `space` provider: codepoint to advance in pixels. The advance is a float in the codec, and a
     * negative one moves the cursor left, which is the whole mechanism behind pixel-accurate placement.
     */
    data class Space(
        val advances: Map<Int, Int>,
        override val filter: ProviderFilter? = null,
    ) : Provider() {
        override fun toJson(): Json = Json.Obj(
            buildList {
                add("type" to Json.Text("space"))
                add(
                    "advances" to Json.Obj(
                        advances.entries
                            .sortedBy { entry -> entry.key }
                            .map { (codepoint, advance) -> Codepoints.raw(codepoint) to Json.Num(advance) },
                    ),
                )
                appendFilterIfPresent(filter)
            },
        )
    }

    /** A `reference` to another font file, which is how vanilla composes `default`. */
    data class Reference(
        val id: String,
        override val filter: ProviderFilter? = null,
    ) : Provider() {
        override fun toJson(): Json = Json.Obj(
            buildList {
                add("type" to Json.Text("reference"))
                add("id" to Json.Text(id))
                appendFilterIfPresent(filter)
            },
        )
    }

    /**
     * A `ttf` provider (`TrueTypeGlyphProviderDefinition`). Note that vanilla resolves [file] relative to
     * `assets/<namespace>/font/`, not `assets/<namespace>/textures/`.
     */
    data class Ttf(
        val file: String,
        val size: Double = 11.0,
        val oversample: Double = 1.0,
        val shift: Pair<Double, Double> = 0.0 to 0.0,
        val skip: List<String> = emptyList(),
        override val filter: ProviderFilter? = null,
    ) : Provider() {
        init {
            require(file.isNotBlank()) { "A ttf provider requires a non-blank 'file'" }
            require(size > 0.0 && size.isFinite()) { "ttf 'size' must be positive, got $size" }
            require(oversample > 0.0 && oversample.isFinite()) { "ttf 'oversample' must be positive, got $oversample" }
            require(shift.first.isFinite() && shift.second.isFinite()) { "ttf 'shift' coordinates must be finite" }
        }

        override fun toJson(): Json = Json.Obj(
            buildList {
                add("type" to Json.Text("ttf"))
                add("file" to Json.Text(file))
                add("size" to Json.Dec(this@Ttf.size))
                add("oversample" to Json.Dec(oversample))
                add("shift" to Json.Arr(listOf(Json.Dec(shift.first), Json.Dec(shift.second))))
                if (skip.isNotEmpty()) {
                    val skipJson = if (skip.size == 1) Json.Text(skip.single()) else Json.Arr(skip.map(Json::Text))
                    add("skip" to skipJson)
                }
                appendFilterIfPresent(filter)
            },
        )
    }

    /** A `unihex` provider (`UnihexProvider.Definition`) backed by a zip archive of `.hex` files. */
    data class Unihex(
        val hexFile: String,
        val sizeOverrides: List<UnihexSizeOverride> = emptyList(),
        override val filter: ProviderFilter? = null,
    ) : Provider() {
        init {
            require(hexFile.isNotBlank()) { "A unihex provider requires a non-blank 'hex_file'" }
        }

        override fun toJson(): Json = Json.Obj(
            buildList {
                add("type" to Json.Text("unihex"))
                add("hex_file" to Json.Text(hexFile))
                if (sizeOverrides.isNotEmpty()) {
                    add("size_overrides" to Json.Arr(sizeOverrides.map(UnihexSizeOverride::toJson)))
                }
                appendFilterIfPresent(filter)
            },
        )
    }
}

/**
 * A font file.
 *
 * Worth knowing before worrying about clobbering vanilla: the client loads *every* pack's copy of a font
 * id and concatenates the providers, so a file listing three of its own providers adds them rather than
 * replacing the default font. Later providers win over earlier ones for the same codepoint, which is why
 * generated providers are appended in a stable order.
 */
data class FontFile(val providers: List<Provider>) {
    fun render(): String = Json.Obj("providers" to Json.Arr(providers.map { provider -> provider.toJson() })).render() + "\n"

    companion object {
        /** Pads a codepoint row out to [width] slots with the codepoint the client skips. */
        fun padRow(row: String, width: Int): String {
            val slots = row.codePointCount(0, row.length)
            if (slots >= width) return row
            return row + Codepoints.raw(GlyphMath.PADDING_CODEPOINT).repeat(width - slots)
        }
    }
}
