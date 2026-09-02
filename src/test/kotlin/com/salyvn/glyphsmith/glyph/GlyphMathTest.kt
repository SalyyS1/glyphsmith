package com.salyvn.glyphsmith.glyph

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The formulas the client applies, checked against values worked out by hand.
 *
 * These are the numbers everything else in the tool is built on, and they are not obvious: the advance is
 * measured from ink rather than from the cell, it always has one pixel added to it, and an empty cell is
 * not free.
 */
class GlyphMathTest {

    @Test
    fun `a cell is the image divided by the grid, with the remainder dropped`() {
        assertEquals(16, GlyphMath.cellWidth(256, 16))
        assertEquals(8, GlyphMath.cellHeight(8, 1))
        // 130 / 4 is 32, so two columns of the source are silently discarded by the client.
        assertEquals(32, GlyphMath.cellWidth(130, 4))
    }

    @Test
    fun `an opaque nine pixel cell at one to one advances ten`() {
        val scale = GlyphMath.pixelScale(height = 9, cellHeight = 9)
        assertEquals(1.0f, scale)
        assertEquals(10, GlyphMath.advance(inkWidth = 9, pixelScale = scale))
    }

    @Test
    fun `an empty cell still advances one pixel`() {
        assertEquals(1, GlyphMath.advance(inkWidth = 0, pixelScale = 1.0f))
    }

    @Test
    fun `halving the scale halves the advance before the pixel is added`() {
        val scale = GlyphMath.pixelScale(height = 8, cellHeight = 16)
        assertEquals(0.5f, scale)
        // 5 * 0.5 = 2.5, plus 0.5 is 3.0, truncated to 3, plus the unconditional 1.
        assertEquals(4, GlyphMath.advance(inkWidth = 5, pixelScale = scale))
    }

    @Test
    fun `ascent seven puts the top edge on the line the text was drawn at`() {
        assertEquals(0, GlyphMath.topOffset(GlyphMath.BASELINE))
        assertEquals(GlyphMath.BASELINE, GlyphMath.ascentFor(0))
    }

    @Test
    fun `lifting a glyph raises the ascent past seven`() {
        assertEquals(13, GlyphMath.ascentFor(-6))
        assertEquals(-6, GlyphMath.topOffset(13))
    }

    @Test
    fun `rendered size follows the declared height rather than the source`() {
        assertEquals(64, GlyphMath.renderedHeight(64))
        assertEquals(32.0f, GlyphMath.renderedWidth(cellWidth = 16, pixelScale = 2.0f))
    }
}
