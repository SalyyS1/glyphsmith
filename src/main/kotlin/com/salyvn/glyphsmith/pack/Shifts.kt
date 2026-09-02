package com.salyvn.glyphsmith.pack

import com.salyvn.glyphsmith.codepoint.Codepoints
import kotlin.math.abs

/**
 * The negative-space glyphs that make horizontal placement possible.
 *
 * A `space` provider maps a codepoint to an advance, and a negative advance moves the cursor left, so a
 * set of powers of two in both directions can express any whole-pixel offset in at most one glyph per
 * bit. They live in the same font file as the images, which means a snippet needs one font switch instead
 * of two.
 */
class Shifts(private val byPixels: Map<Int, Int>) {
    val steps: List<Int> get() = byPixels.keys.sortedDescending()

    val codepoints: Collection<Int> get() = byPixels.values

    fun provider(): Provider.Space = Provider.Space(byPixels.entries.associate { (pixels, codepoint) -> codepoint to pixels })

    /** The characters that move the cursor [pixels] to the right, or left when negative. */
    fun string(pixels: Int): String {
        if (pixels == 0) return ""
        val sign = if (pixels < 0) -1 else 1
        var remaining = abs(pixels)
        val available = byPixels.keys.filter { step -> step > 0 }.sortedDescending()
        require(available.isNotEmpty()) { "This font has no shift glyphs" }
        val out = StringBuilder()
        while (remaining > 0) {
            val step = available.firstOrNull { candidate -> candidate <= remaining }
                ?: throw IllegalStateException("No shift glyph is small enough for ${remaining}px")
            val codepoint = byPixels[step * sign]
                ?: throw IllegalStateException("This font has no ${step * sign}px shift glyph")
            out.append(Codepoints.raw(codepoint))
            remaining -= step
        }
        return out.toString()
    }

    /** How many glyphs [pixels] costs, which is what decides whether the range is worth widening. */
    fun cost(pixels: Int): Int = string(pixels).codePointCount(0, string(pixels).length)

    companion object {
        /** 1, 2, 4 … up to and including the largest power of two that fits [maxMagnitude]. */
        fun powersOfTwo(maxMagnitude: Int): List<Int> {
            require(maxMagnitude >= 1) { "A shift range needs to reach at least 1px" }
            val powers = mutableListOf<Int>()
            var step = 1
            while (step <= maxMagnitude) {
                powers += step
                step *= 2
            }
            return powers
        }

        /**
         * Allocates a codepoint for every step in both directions. [allocate] is the project's allocator,
         * so the ids stay stable across rebuilds the same way image glyphs do.
         */
        fun build(maxMagnitude: Int, allocate: (String) -> Int): Shifts {
            val byPixels = LinkedHashMap<Int, Int>()
            powersOfTwo(maxMagnitude).forEach { step ->
                byPixels[step] = allocate("shift:+$step")
                byPixels[-step] = allocate("shift:-$step")
            }
            return Shifts(byPixels)
        }
    }
}
