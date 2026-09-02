package com.salyvn.glyphsmith.codepoint

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CodepointAllocatorTest {

    @Test
    fun `ids keep the codepoint they were given`() {
        val first = CodepointAllocator(Codepoints.SUPPLEMENTARY_PRIVATE_USE_FIRST)
        val panel = first.allocate("ui.panel")
        val coin = first.allocate("hud.coin")

        // A second run reads the assignments back and must not renumber, even though the spec now lists a
        // new glyph before the old ones.
        val second = CodepointAllocator(Codepoints.SUPPLEMENTARY_PRIVATE_USE_FIRST, first.assignments())
        assertEquals(panel, second.allocate("ui.panel"))
        assertEquals(coin, second.allocate("hud.coin"))
        val added = second.allocate("ui.tab")
        assertTrue(added > coin, "a new id takes a fresh codepoint after the highest one already handed out")
    }

    @Test
    fun `a run of cells gets consecutive codepoints`() {
        val allocator = CodepointAllocator(0xF0000)
        val run = allocator.allocateRun("banner", 3)

        assertEquals(listOf(0xF0000, 0xF0001, 0xF0002), run)
        assertEquals(3, allocator.assignments().size)
        assertEquals(0xF0000, allocator.allocate("banner"), "the first cell keeps the glyph's own id")
    }

    @Test
    fun `surrogates and non-characters are stepped over`() {
        assertFalse(Codepoints.isUsable(0xD800))
        assertFalse(Codepoints.isUsable(0xFFFE))
        assertFalse(Codepoints.isUsable(0xFFFF))
        assertFalse(Codepoints.isUsable(0x1FFFF))
        assertFalse(Codepoints.isUsable(0))

        assertEquals(0xE000, Codepoints.next(0xD7FF), "the whole surrogate block is skipped in one step")
        assertEquals(0x10000, Codepoints.next(0xFFFD))
    }

    @Test
    fun `a codepoint is written three ways`() {
        assertEquals("U+E000", Codepoints.describe(0xE000))
        assertEquals("\\uE000", Codepoints.escape(0xE000))
        assertEquals(1, Codepoints.raw(0xE000).length)

        // Above the BMP a codepoint is a surrogate pair, which is what a Java or JSON escape has to spell.
        assertEquals(2, Codepoints.raw(0xF0000).length)
        assertEquals("\\uDB80\\uDC00", Codepoints.escape(0xF0000))
    }

    @Test
    fun `codepoints parse from the spellings people use`() {
        assertEquals(0xF0000, Codepoints.parse("U+F0000"))
        assertEquals(0xF0000, Codepoints.parse("0xF0000"))
        assertEquals(0xE123, Codepoints.parse("e123"))
    }
}
