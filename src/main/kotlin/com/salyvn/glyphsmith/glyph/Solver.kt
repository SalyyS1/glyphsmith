package com.salyvn.glyphsmith.glyph

import com.salyvn.glyphsmith.image.Texture

/** Where the author wants the glyph's top-left corner, in pixels relative to an anchor's text origin. */
data class Placement(val x: Int, val y: Int)

/** Raised when a request cannot be satisfied by any legal provider, with the reason a human needs. */
class GlyphSolveException(message: String) : Exception(message)

/**
 * One glyph, solved: the texture as it must be written out, the two numbers the provider declares, and
 * the numbers the client will derive from them.
 */
data class SolvedGlyph(
    val texture: Texture,
    val columns: Int,
    val cellWidth: Int,
    val cellHeight: Int,
    val height: Int,
    val ascent: Int,
    val advances: List<Int>,
    val leadShift: Int,
    val padBottom: Int,
    val padRight: Int,
) {
    val pixelScale: Float get() = GlyphMath.pixelScale(height, cellHeight)

    /** How far the cursor has moved once every cell has been drawn. */
    val totalAdvance: Int get() = advances.sum()

    /** The shift that puts the cursor back where it started, so following text is not displaced. */
    val restoreShift: Int get() = -(leadShift + totalAdvance)

    /** Per cell: the gap between where the cell's pixels end and where the cursor ended up. */
    fun seamCorrections(): List<Int> = advances.map { advance -> (cellWidth * height / cellHeight) - advance }
}

/**
 * Turns "put these pixels there" into a legal `bitmap` provider.
 *
 * The whole difficulty is one codec rule — `ascent <= height` — colliding with one derived value —
 * `pixelScale = height / cellHeight`. Raising `ascent` to lift a glyph forces `height` up with it, and
 * `height` is also the scale factor, so the texture magnifies unless the cell grows by the same amount.
 * Padding the source with transparent rows at the bottom grows the cell without moving the visible
 * pixels, which is the trick this solver applies for you.
 */
object Solver {
    fun solve(
        texture: Texture,
        placement: Placement?,
        scale: Int = 1,
        ascentOverride: Int? = null,
        heightOverride: Int? = null,
        maxCellWidth: Int = GlyphMath.MAX_CELL,
    ): SolvedGlyph {
        if (scale < 1) throw GlyphSolveException("scale must be at least 1, got $scale")
        if (texture.width == 0 || texture.height == 0) throw GlyphSolveException("The image has no pixels")
        if (texture.height > GlyphMath.MAX_CELL) {
            throw GlyphSolveException(
                "The image is ${texture.height}px tall and one font atlas page is only ${GlyphMath.MAX_CELL}px, " +
                    "so the client would silently draw the missing-glyph box. Split it into stacked glyphs or " +
                    "scale the source down.",
            )
        }

        // A source wider than one cell is cut into columns; the client divides by integer division, so the
        // width has to be a multiple of the column count or the rightmost pixels vanish without a word.
        val columns = ((texture.width + maxCellWidth - 1) / maxCellWidth).coerceAtLeast(1)
        val cellWidth = (texture.width + columns - 1) / columns
        val padRight = cellWidth * columns - texture.width

        val requestedAscent = ascentOverride ?: placement?.let { GlyphMath.ascentFor(it.y) } ?: GlyphMath.BASELINE

        // Grow the cell until the ascent the placement needs is a legal one, then let `height` follow so
        // the texture still renders at the requested scale.
        val padBottom = when {
            heightOverride != null -> 0
            requestedAscent <= texture.height * scale -> 0
            else -> ceilDiv(requestedAscent, scale) - texture.height
        }
        val cellHeight = texture.height + padBottom
        val height = heightOverride ?: (cellHeight * scale)

        if (requestedAscent > height) {
            throw GlyphSolveException(
                "ascent $requestedAscent is higher than height $height, which the client refuses for the whole " +
                    "font file. Raise height to at least $requestedAscent, or drop the glyph by " +
                    "${requestedAscent - height}px.",
            )
        }
        if (cellWidth > GlyphMath.MAX_CELL || cellHeight > GlyphMath.MAX_CELL) {
            throw GlyphSolveException(
                "A cell of ${cellWidth}x${cellHeight}px does not fit a ${GlyphMath.MAX_CELL}px atlas page; the " +
                    "client would draw the missing-glyph box with nothing in the log.",
            )
        }

        val grown = texture.grown(padBottom, padRight)
        val scaleFactor = GlyphMath.pixelScale(height, cellHeight)
        val advances = (0 until columns).map { column ->
            GlyphMath.advance(grown.inkWidth(column * cellWidth, 0, cellWidth, cellHeight), scaleFactor)
        }

        return SolvedGlyph(
            texture = grown,
            columns = columns,
            cellWidth = cellWidth,
            cellHeight = cellHeight,
            height = height,
            ascent = requestedAscent,
            advances = advances,
            leadShift = placement?.x ?: 0,
            padBottom = padBottom,
            padRight = padRight,
        )
    }

    private fun ceilDiv(value: Int, by: Int): Int = (value + by - 1) / by
}
