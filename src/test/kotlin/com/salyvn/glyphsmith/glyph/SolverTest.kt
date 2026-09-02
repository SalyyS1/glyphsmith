package com.salyvn.glyphsmith.glyph

import com.salyvn.glyphsmith.image.Texture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SolverTest {

    private fun opaque(width: Int, height: Int): Texture {
        val texture = Texture.blank(width, height)
        for (x in 0 until width) {
            for (y in 0 until height) texture.image.setRGB(x, y, 0xFF808080.toInt())
        }
        return texture
    }

    @Test
    fun `a glyph asked for above the line is padded until its ascent is legal`() {
        // A nine pixel coin whose top edge should sit six pixels above the text origin needs ascent 13,
        // which is illegal against a nine pixel cell. Padding the bottom grows the cell instead of
        // magnifying the artwork.
        val solved = Solver.solve(opaque(9, 9), Placement(0, -6))

        assertEquals(13, solved.ascent)
        assertEquals(13, solved.height)
        assertEquals(4, solved.padBottom)
        assertEquals(13, solved.cellHeight)
        assertEquals(1.0f, solved.pixelScale, "the artwork must still render one source pixel per screen pixel")
        assertEquals(9, solved.texture.inkHeight(), "padding is transparent, so the visible pixels do not grow")
    }

    @Test
    fun `a glyph that already fits is not padded`() {
        val solved = Solver.solve(opaque(176, 64), Placement(-8, -6))

        assertEquals(13, solved.ascent)
        assertEquals(64, solved.height)
        assertEquals(0, solved.padBottom)
        assertEquals(-8, solved.leadShift)
    }

    @Test
    fun `the ascent is never higher than the height`() {
        listOf(-40, -13, -1, 0, 6, 7, 30).forEach { offset ->
            val solved = Solver.solve(opaque(9, 9), Placement(0, offset))
            assertTrue(
                solved.ascent <= solved.height,
                "ascent ${solved.ascent} must not exceed height ${solved.height} for offset $offset",
            )
        }
    }

    @Test
    fun `an advance is derived from ink and not from the cell`() {
        val texture = Texture.blank(16, 16)
        // Ink in the first five columns only; the rest of the cell is transparent.
        for (x in 0 until 5) {
            for (y in 0 until 16) texture.image.setRGB(x, y, 0xFFFFFFFF.toInt())
        }
        val solved = Solver.solve(texture, placement = null, ascentOverride = 8, heightOverride = 16)

        assertEquals(6, solved.advances.single(), "five pixels of ink advance six")
    }

    @Test
    fun `an image wider than an atlas page is split into cells that divide evenly`() {
        val solved = Solver.solve(opaque(600, 32), placement = null, ascentOverride = 8, heightOverride = 32)

        assertEquals(3, solved.columns)
        assertEquals(200, solved.cellWidth)
        assertEquals(0, solved.padRight)
        assertEquals(3, solved.advances.size)
    }

    @Test
    fun `a width that does not divide evenly is padded rather than truncated`() {
        val solved = Solver.solve(opaque(601, 32), placement = null, ascentOverride = 8, heightOverride = 32)

        assertEquals(3, solved.columns)
        assertEquals(201, solved.cellWidth)
        assertEquals(2, solved.padRight)
        assertEquals(603, solved.texture.width)
    }

    @Test
    fun `an image taller than an atlas page is refused with the reason`() {
        val failure = assertThrows<GlyphSolveException> { Solver.solve(opaque(32, 300), Placement(0, 0)) }

        assertTrue(failure.message!!.contains("256"), "the message should name the limit: ${failure.message}")
    }

    @Test
    fun `an impossible manual pair is refused with the numbers to change`() {
        val failure = assertThrows<GlyphSolveException> {
            Solver.solve(opaque(9, 9), placement = null, ascentOverride = 20, heightOverride = 9)
        }

        assertTrue(failure.message!!.contains("20"))
        assertTrue(failure.message!!.contains("9"))
    }

    @Test
    fun `restoring the cursor undoes the lead shift and every advance`() {
        val solved = Solver.solve(opaque(176, 64), Placement(-8, -6))

        assertEquals(-(solved.leadShift + solved.totalAdvance), solved.restoreShift)
    }
}
