package com.salyvn.glyphsmith.pack

import com.salyvn.glyphsmith.codepoint.CodepointAllocator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShiftsTest {

    private fun shifts(range: Int = 1024): Shifts {
        val allocator = CodepointAllocator(0xF0000)
        return Shifts.build(range, allocator::allocate)
    }

    /** Reads a shift run back into pixels, the way the client's `advances` map would. */
    private fun pixelsOf(shifts: Shifts, run: String): Int {
        val byCodepoint = shifts.provider().advances
        var total = 0
        var index = 0
        while (index < run.length) {
            val codepoint = run.codePointAt(index)
            total += byCodepoint.getValue(codepoint)
            index += Character.charCount(codepoint)
        }
        return total
    }

    @Test
    fun `steps are powers of two up to the range`() {
        assertEquals(listOf(1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024), Shifts.powersOfTwo(1024))
        assertEquals(listOf(1, 2, 4, 8), Shifts.powersOfTwo(15), "the range is a ceiling, not a target")
    }

    @Test
    fun `every whole pixel offset in range is expressed exactly`() {
        val shifts = shifts()

        (-300..300).forEach { pixels ->
            assertEquals(pixels, pixelsOf(shifts, shifts.string(pixels)), "shift of ${pixels}px")
        }
    }

    @Test
    fun `an offset costs one glyph per bit`() {
        val shifts = shifts()

        assertEquals("", shifts.string(0))
        assertEquals(1, shifts.cost(8), "a power of two is a single glyph")
        assertEquals(3, shifts.cost(-13), "13 is 8 + 4 + 1")
    }

    @Test
    fun `an offset past the range is still exact, just longer`() {
        val shifts = shifts(range = 16)

        assertEquals(-100, pixelsOf(shifts, shifts.string(-100)))
        assertTrue(shifts.cost(-100) > 4, "without a large step it takes several of the largest one")
    }

    @Test
    fun `both directions exist for every step`() {
        val shifts = shifts(range = 8)
        val advances = shifts.provider().advances.values.toSet()

        assertEquals(setOf(1, -1, 2, -2, 4, -4, 8, -8), advances)
    }
}
