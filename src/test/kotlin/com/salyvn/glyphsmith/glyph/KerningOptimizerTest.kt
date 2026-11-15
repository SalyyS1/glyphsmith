package com.salyvn.glyphsmith.glyph

import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import com.salyvn.glyphsmith.GlyphsmithCli
import com.salyvn.glyphsmith.cli.KernCommand
import com.salyvn.glyphsmith.codepoint.CodepointAllocator
import com.salyvn.glyphsmith.image.Texture
import com.salyvn.glyphsmith.pack.Shifts
import com.salyvn.glyphsmith.ttf.OpenTypeFont
import com.salyvn.glyphsmith.ttf.SfntFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class KerningOptimizerTest {

    private fun sumRun(shifts: Shifts, run: String): Int {
        val map = shifts.provider().advances
        var sum = 0
        var i = 0
        while (i < run.length) {
            val cp = run.codePointAt(i)
            sum += map.getValue(cp)
            i += Character.charCount(cp)
        }
        return sum
    }

    @Test
    fun `signed-digit NAF minimal shift encoding is exact across -2000_2000 and strictly shrinks bit runs`() {
        val allocator = CodepointAllocator(0xF0000)
        val shifts = Shifts.build(1024, allocator::allocate)

        for (px in -2000..2000) {
            val minRun = shifts.minimalString(px)
            assertEquals(px, sumRun(shifts, minRun), "exact pixel total for ${px}px")
            assertTrue(
                shifts.minimalCost(px) <= shifts.cost(px),
                "minimalCost(${px})=${shifts.minimalCost(px)} must never exceed standard cost=${shifts.cost(px)}",
            )
        }

        // 255 = +256 - 1 (2 glyphs vs 8 standard)
        assertEquals(8, shifts.cost(255))
        assertEquals(2, shifts.minimalCost(255))
        // -63 = -64 + 1 (2 glyphs vs 6 standard)
        assertEquals(6, shifts.cost(-63))
        assertEquals(2, shifts.minimalCost(-63))
        // -511 = -512 + 1 (2 glyphs vs 9 standard)
        assertEquals(9, shifts.cost(-511))
        assertEquals(2, shifts.minimalCost(-511))
        // Tie-break preserves single-sign representation on -13 (-8, -4, -1 = 3 glyphs)
        assertEquals(shifts.string(-13), shifts.minimalString(-13))

        // Bounded max step: 31 with maxMagnitude=16 -> +16 +16 -1 (3 glyphs vs 5 standard)
        val bounded = Shifts.build(16, CodepointAllocator(0xF1000)::allocate)
        assertEquals(31, sumRun(bounded, bounded.minimalString(31)))
        assertEquals(5, bounded.cost(31))
        assertEquals(3, bounded.minimalCost(31))
    }

    @Test
    fun `solves optical bitmap pair kerning for rectangular, overhanging L+T, and disjoint glyphs`(@TempDir dir: Path) {
        // 1. Two solid 8x8 blocks: vanilla advance = 9px, natural gap = 1px -> kerning = 0px
        val block = Texture.blank(8, 8)
        for (x in 0 until 8) for (y in 0 until 8) block.image.setRGB(x, y, 0xFFFFFFFF.toInt())
        val solvedBlock = Solver.solve(block, placement = null, heightOverride = 8, ascentOverride = 7)
        val blockPair = KerningOptimizer.solveBitmapPair("blockA", solvedBlock, "blockB", solvedBlock, targetGapPx = 1)
        assertEquals(1, blockPair.naturalGapPx)
        assertEquals(0, blockPair.kerningPx)

        // Seamless bar join (targetGapPx = 0) tucks by -1px
        val barJoin = KerningOptimizer.solveBitmapPair("segA", solvedBlock, "segB", solvedBlock, targetGapPx = 0)
        assertEquals(-1, barJoin.kerningPx)

        // 2. Overhanging 'L' (stem x=0..1, bottom bar x=0..6 on y=6..7) + 'T' (top bar x=0..6 on y=0..1, stem x=3..4)
        val glyphL = Texture.blank(8, 8)
        for (y in 0 until 8) for (x in 0..1) glyphL.image.setRGB(x, y, 0xFFFFFFFF.toInt())
        for (y in 6..7) for (x in 0..6) glyphL.image.setRGB(x, y, 0xFFFFFFFF.toInt())

        val glyphT = Texture.blank(8, 8)
        for (y in 0..1) for (x in 0..6) glyphT.image.setRGB(x, y, 0xFFFFFFFF.toInt())
        for (y in 0 until 8) for (x in 3..4) glyphT.image.setRGB(x, y, 0xFFFFFFFF.toInt())

        val pathL = dir.resolve("L.png").also(glyphL::write)
        val pathT = dir.resolve("T.png").also(glyphT::write)

        val solvedL = Solver.solve(glyphL, placement = null, heightOverride = 8, ascentOverride = 7)
        val solvedT = Solver.solve(glyphT, placement = null, heightOverride = 8, ascentOverride = 7)

        val ltPair = KerningOptimizer.solveBitmapPair("L", solvedL, "T", solvedT, targetGapPx = 1)
        assertEquals(8, ltPair.leftAdvancePx)
        assertEquals(4, ltPair.naturalGapPx)
        assertEquals(-3, ltPair.kerningPx)

        // Render kerned sequence and verify exact shift insertion
        val allocator = CodepointAllocator(0xF0000)
        val shifts = Shifts.build(64, allocator::allocate)
        val cpL = allocator.allocate("L")
        val cpT = allocator.allocate("T")
        val seq = KerningOptimizer.renderKernedSequence(
            glyphCodepoints = listOf("L" to listOf(cpL), "T" to listOf(cpT)),
            kerningTable = mapOf(("L" to "T") to ltPair.kerningPx),
            shifts = shifts,
        )
        assertEquals(4, seq.codePointCount(0, seq.length), "L + (-2px, -1px) + T = 4 codepoints")

        // 3. Vertically disjoint glyphs (top row y=0 vs bottom row y=7) -> naturalGapPx = null, kerning = 0
        val topDot = Texture.blank(4, 8).apply { image.setRGB(0, 0, 0xFFFFFFFF.toInt()) }
        val botDot = Texture.blank(4, 8).apply { image.setRGB(0, 7, 0xFFFFFFFF.toInt()) }
        val disjoint = KerningOptimizer.solveBitmapPair(
            "top",
            Solver.solve(topDot, null, heightOverride = 8, ascentOverride = 7),
            "bot",
            Solver.solve(botDot, null, heightOverride = 8, ascentOverride = 7),
        )
        assertNull(disjoint.naturalGapPx)
        assertEquals(0, disjoint.kerningPx)

        // 4. Verify CLI KernCommand runs cleanly
        val cli = GlyphsmithCli().subcommands(KernCommand())
        cli.parse(listOf("kern", pathL.toString(), pathT.toString(), "--json"))
    }

    @Test
    fun `quantizes OpenType kerning pairs to GUI pixels, prunes sub-pixel noise, and extracts dominant biases`() {
        // 'A'->'V' = -120u, 'A'->'T' = -120u, 'V'->'T' = -20u (sub-pixel at size=11.0, 1000 upm)
        val bytes = SfntFixtures.buildStaticFont(
            codepoints = listOf('A'.code, 'V'.code, 'T'.code),
            kernPairs = listOf(
                Triple(1, 2, -120),
                Triple(1, 3, -120),
                Triple(2, 3, -20),
            ),
        )
        val font = OpenTypeFont.parse(bytes)
        val opt = KerningOptimizer.optimizeOpenTypePairs(font, sizePx = 11.0)

        assertEquals(3, opt.rawPairCount)
        assertEquals(2, opt.activePixelPairCount)
        assertEquals(1, opt.prunedSubpixelCount, "-20u * 11 / 1000 = -0.22px rounds to 0px and is pruned")
        // Both 'A'->'V' and 'A'->'T' round to -1px, so 'A' (U+0041) gets a -1px bias and 0 residual pairs!
        assertEquals(-1, opt.leftAdvanceBiases["U+0041"])
        assertEquals(0, opt.residualPairCount)
    }
}
