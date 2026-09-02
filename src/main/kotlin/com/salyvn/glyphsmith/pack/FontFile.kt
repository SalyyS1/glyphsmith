package com.salyvn.glyphsmith.pack

import com.salyvn.glyphsmith.codepoint.Codepoints
import com.salyvn.glyphsmith.glyph.GlyphMath

/** One entry in a font file's `providers` array. */
sealed class Provider {
    abstract fun toJson(): Json

    /**
     * A `bitmap` provider. [chars] is the codepoint grid: one string per row, every row the same length,
     * padded with ` ` where a slot is empty, because unequal rows are rejected with
     * "Lines in codepoint grid have to be the same length".
     */
    data class Bitmap(
        val file: String,
        val height: Int,
        val ascent: Int,
        val chars: List<String>,
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
            "type" to Json.Text("bitmap"),
            "file" to Json.Text(file),
            "height" to Json.Num(height),
            "ascent" to Json.Num(ascent),
            "chars" to Json.Arr(chars.map { row -> Json.Text(row) }),
        )
    }

    /**
     * A `space` provider: codepoint to advance in pixels. The advance is a float in the codec, and a
     * negative one moves the cursor left, which is the whole mechanism behind pixel-accurate placement.
     */
    data class Space(val advances: Map<Int, Int>) : Provider() {
        override fun toJson(): Json = Json.Obj(
            "type" to Json.Text("space"),
            "advances" to Json.Obj(
                advances.entries
                    .sortedBy { entry -> entry.key }
                    .map { (codepoint, advance) -> Codepoints.raw(codepoint) to Json.Num(advance) },
            ),
        )
    }

    /** A `reference` to another font file, which is how vanilla composes `default`. */
    data class Reference(val id: String) : Provider() {
        override fun toJson(): Json = Json.Obj(
            "type" to Json.Text("reference"),
            "id" to Json.Text(id),
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
