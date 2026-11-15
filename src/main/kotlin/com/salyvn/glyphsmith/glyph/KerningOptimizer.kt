package com.salyvn.glyphsmith.glyph

import com.salyvn.glyphsmith.codepoint.Codepoints
import com.salyvn.glyphsmith.pack.Shifts
import com.salyvn.glyphsmith.ttf.OpenTypeFont
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

@Serializable
data class BitmapKerningPair(
    val leftId: String,
    val rightId: String,
    val leftAdvancePx: Int,
    val naturalGapPx: Int?,
    val targetGapPx: Int,
    val kerningPx: Int,
    val standardShiftGlyphs: Int,
    val minimalShiftGlyphs: Int,
) {
    fun toJsonString(): String = JSON_OUT.encodeToString(this)

    companion object {
        private val JSON_OUT = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
            encodeDefaults = true
        }
    }
}

@Serializable
data class QuantizedKerningPair(
    val leftCodepoint: String,
    val rightCodepoint: String,
    val leftChar: String,
    val rightChar: String,
    val designUnits: Int,
    val pixelDelta: Int,
    val residualPixelDelta: Int,
    val source: String,
)

@Serializable
data class OpenTypeKerningOptimization(
    val sizePx: Double,
    val unitsPerEm: Int,
    val rawPairCount: Int,
    val activePixelPairCount: Int,
    val prunedSubpixelCount: Int,
    val leftAdvanceBiases: Map<String, Int>,
    val residualPairCount: Int,
    val pairs: List<QuantizedKerningPair>,
) {
    fun toJsonString(): String = JSON_OUT.encodeToString(this)

    companion object {
        private val JSON_OUT = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
            encodeDefaults = true
        }
    }
}

/**
 * Optical bitmap kerning solver, OpenType kerning table quantizer, and bounded signed-digit
 * Non-Adjacent Form (NAF) shift sequence optimizer.
 */
object KerningOptimizer {

    /**
     * Computes the provably minimum-length signed power-of-two decomposition of [pixels] using steps from
     * `{ +/-s : s in availableSteps }`.
     *
     * Because a `Shifts` space provider already allocates both `+2^k` and `-2^k` for every power of two up
     * to `maxMagnitude`, runs of adjacent 1-bits collapse into two glyphs (`+2^(k+1) - 2^j`): for instance,
     * `255px` (`0b11111111`) takes 2 shift glyphs (`+256, -1`) instead of 8, and `-63px` takes 2 (`-64, +1`)
     * instead of 6. Ties are broken in favor of same-sign greedy decomposition so single-sign offsets keep
     * their familiar representation when no glyphs are saved.
     */
    fun minimalShiftSteps(pixels: Int, availableSteps: List<Int>): List<Int> {
        if (pixels == 0) return emptyList()
        val sortedAsc = availableSteps.filter { it > 0 }.distinct().sorted()
        require(sortedAsc.isNotEmpty() && sortedAsc.first() == 1) {
            "minimalShiftSteps requires power-of-two shift steps starting at 1px"
        }

        // Verify contiguous powers of two 1, 2, ..., 2^(K-1); fall back to greedy if custom non-binary steps.
        val kCount = sortedAsc.size
        val isContiguousPowers = sortedAsc.withIndex().all { (idx, v) -> v == (1 shl idx) }
        if (!isContiguousPowers) {
            return greedySteps(pixels, sortedAsc.asReversed())
        }

        val sign = if (pixels < 0) -1 else 1
        val mag = abs(pixels.toLong())
        val maxStep = sortedAsc.last().toLong() // 2^(kCount - 1)

        // Decompose over bits 0..(kCount - 2), leaving multiples of maxStep = 2^(kCount - 1) at the top.
        val lowBits = kCount - 1
        if (lowBits == 0) {
            // Only +/-1 exists
            return List(mag.toInt()) { sign }
        }

        val topBlock = 1L shl lowBits // == maxStep
        val q = (mag / topBlock).toInt()
        val r = (mag % topBlock).toInt()

        // dp0: minimal list of signed steps from {+/-1..+/-2^(i-1)} summing to (r mod 2^i)
        // dp1: minimal list of signed steps from {+/-1..+/-2^(i-1)} summing to (r mod 2^i) - 2^i
        var dp0: List<Int>? = emptyList()
        var dp1: List<Int>? = null

        for (i in 0 until lowBits) {
            val bit = (r ushr i) and 1
            val stepVal = 1 shl i
            var next0: List<Int>? = null
            var next1: List<Int>? = null

            // Transition from carry_in = 0 (s = bit)
            dp0?.let { prev ->
                if (bit == 0) {
                    next0 = pickBetter(next0, prev)
                } else {
                    // bit == 1: digit = +1 gives carry_out = 0; digit = -1 gives carry_out = 1
                    next0 = pickBetter(next0, prev + stepVal)
                    next1 = pickBetter(next1, prev + (-stepVal))
                }
            }

            // Transition from carry_in = 1 (s = bit + 1)
            dp1?.let { prev ->
                if (bit == 0) {
                    // s = 1: digit = +1 gives carry_out = 0; digit = -1 gives carry_out = 1
                    next0 = pickBetter(next0, prev + stepVal)
                    next1 = pickBetter(next1, prev + (-stepVal))
                } else {
                    // s = 2: digit = 0 gives carry_out = 1
                    next1 = pickBetter(next1, prev)
                }
            }

            dp0 = next0
            dp1 = next1
        }

        val cand0 = dp0?.let { low -> List(q) { maxStep.toInt() } + low }
        val cand1 = dp1?.let { low -> List(q + 1) { maxStep.toInt() } + low }
        val chosen = pickBetter(cand0, cand1) ?: greedySteps(mag.toInt(), sortedAsc.asReversed())

        // Order largest magnitude first and apply overall sign.
        return chosen
            .sortedByDescending { abs(it) }
            .map { it * sign }
    }

    private fun pickBetter(current: List<Int>?, candidate: List<Int>?): List<Int>? {
        if (candidate == null) return current
        if (current == null) return candidate
        if (candidate.size != current.size) {
            return if (candidate.size < current.size) candidate else current
        }
        // Tie-break: prefer fewer negative adjustments (same-sign greedy canonical form).
        val curNeg = current.count { it < 0 }
        val candNeg = candidate.count { it < 0 }
        return if (candNeg < curNeg) candidate else current
    }

    private fun greedySteps(pixels: Int, sortedDesc: List<Int>): List<Int> {
        val sign = if (pixels < 0) -1 else 1
        var rem = abs(pixels)
        val out = ArrayList<Int>()
        while (rem > 0) {
            val step = sortedDesc.first { it <= rem }
            out += step * sign
            rem -= step
        }
        return out
    }

    /**
     * Projects [left] and [right] onto Minecraft's screen-pixel baseline grid (`top = 7 - ascent`) and
     * computes the horizontal kerning shift (in GUI pixels) required so the closest ink contour between the
     * two glyphs sits exactly [targetGapPx] pixels apart.
     */
    fun solveBitmapPair(
        leftId: String,
        left: SolvedGlyph,
        rightId: String,
        right: SolvedGlyph,
        targetGapPx: Int = 1,
        diagonalClearance: Boolean = true,
        shiftRange: Int = 1024,
    ): BitmapKerningPair {
        val leftProfile = rasterizeContour(left)
        val rightProfile = rasterizeContour(right)
        val cursorAfterLeft = effectiveUnrestoredAdvance(left)

        var minGap: Int? = null
        val rowRadius = if (diagonalClearance) 1 else 0

        for ((ry, rightMinX) in rightProfile.leftmostByRow) {
            for (dy in -rowRadius..rowRadius) {
                val leftMaxX = leftProfile.rightmostByRow[ry + dy] ?: continue
                val gap = (cursorAfterLeft + rightMinX) - (leftMaxX + 1)
                if (minGap == null || gap < minGap) {
                    minGap = gap
                }
            }
        }

        val kerningPx = if (minGap == null) 0 else targetGapPx - minGap
        val powers = Shifts.powersOfTwo(shiftRange.coerceAtLeast(1))
        val stdCount = greedySteps(kerningPx, powers.asReversed()).size
        val minCount = minimalShiftSteps(kerningPx, powers).size

        return BitmapKerningPair(
            leftId = leftId,
            rightId = rightId,
            leftAdvancePx = cursorAfterLeft,
            naturalGapPx = minGap,
            targetGapPx = targetGapPx,
            kerningPx = kerningPx,
            standardShiftGlyphs = stdCount,
            minimalShiftGlyphs = minCount,
        )
    }

    /** Computes all non-zero optical kerning pairs across an ordered list of solved glyphs. */
    fun solveBitmapMatrix(
        glyphs: List<Pair<String, SolvedGlyph>>,
        targetGapPx: Int = 1,
        diagonalClearance: Boolean = true,
        shiftRange: Int = 1024,
    ): List<BitmapKerningPair> {
        val out = ArrayList<BitmapKerningPair>()
        for ((leftId, leftGlyph) in glyphs) {
            for ((rightId, rightGlyph) in glyphs) {
                val solved = solveBitmapPair(
                    leftId = leftId,
                    left = leftGlyph,
                    rightId = rightId,
                    right = rightGlyph,
                    targetGapPx = targetGapPx,
                    diagonalClearance = diagonalClearance,
                    shiftRange = shiftRange,
                )
                if (solved.kerningPx != 0) {
                    out += solved
                }
            }
        }
        return out
    }

    /**
     * Renders a sequence of inline glyph codepoint runs interleaved with minimal signed-digit kerning shifts.
     */
    fun renderKernedSequence(
        glyphCodepoints: List<Pair<String, List<Int>>>,
        kerningTable: Map<Pair<String, String>, Int>,
        shifts: Shifts,
    ): String {
        if (glyphCodepoints.isEmpty()) return ""
        val sb = StringBuilder()
        glyphCodepoints.forEachIndexed { idx, (id, cps) ->
            cps.forEach { cp -> sb.append(Codepoints.raw(cp)) }
            if (idx < glyphCodepoints.lastIndex) {
                val nextId = glyphCodepoints[idx + 1].first
                val delta = kerningTable[id to nextId] ?: 0
                if (delta != 0) {
                    sb.append(shifts.minimalString(delta))
                }
            }
        }
        return sb.toString()
    }

    /**
     * Quantizes OpenType `kern` and `GPOS` design-unit kerning pairs onto Minecraft's pixel grid at [sizePx],
     * prunes sub-pixel pairs that round to `0px`, and extracts per-left-glyph dominant advance biases.
     */
    fun optimizeOpenTypePairs(font: OpenTypeFont, sizePx: Double = 11.0): OpenTypeKerningOptimization {
        require(sizePx > 0.0 && sizePx.isFinite()) { "sizePx must be positive and finite, got $sizePx" }
        val upm = font.info.unitsPerEm.toDouble()
        val rawPairs = font.info.kerningPairs

        data class ActivePair(
            val leftCp: Int,
            val rightCp: Int,
            val designUnits: Int,
            val px: Int,
            val source: String,
        )

        val active = ArrayList<ActivePair>()
        var pruned = 0
        for (p in rawPairs) {
            val px = ((p.designUnits.toDouble() * sizePx) / upm).roundToInt()
            if (px == 0) {
                pruned++
            } else {
                active += ActivePair(p.leftCodepoint, p.rightCodepoint, p.designUnits, px, p.source)
            }
        }

        // For each left codepoint appearing in >= 2 active pairs, find the non-zero mode delta if it strictly
        // eliminates more pairs than 1.
        val byLeft = active.groupBy { it.leftCp }
        val leftBiases = LinkedHashMap<Int, Int>()
        for ((leftCp, group) in byLeft) {
            if (group.size < 2) continue
            val freq = group.groupingBy { it.px }.eachCount()
            val best = freq.entries
                .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { abs(it.key) })
                .first()
            if (best.value >= 2) {
                leftBiases[leftCp] = best.key
            }
        }

        val quantized = active.map { p ->
            val bias = leftBiases[p.leftCp] ?: 0
            QuantizedKerningPair(
                leftCodepoint = Codepoints.describe(p.leftCp),
                rightCodepoint = Codepoints.describe(p.rightCp),
                leftChar = printablePreview(p.leftCp),
                rightChar = printablePreview(p.rightCp),
                designUnits = p.designUnits,
                pixelDelta = p.px,
                residualPixelDelta = p.px - bias,
                source = p.source,
            )
        }
        val residualCount = quantized.count { it.residualPixelDelta != 0 }

        return OpenTypeKerningOptimization(
            sizePx = sizePx,
            unitsPerEm = font.info.unitsPerEm,
            rawPairCount = rawPairs.size,
            activePixelPairCount = active.size,
            prunedSubpixelCount = pruned,
            leftAdvanceBiases = leftBiases.entries.associate { (cp, b) -> Codepoints.describe(cp) to b },
            residualPairCount = residualCount,
            pairs = quantized,
        )
    }

    private fun printablePreview(cp: Int): String =
        if (cp in 0x21..0x7E) String(Character.toChars(cp)) else Codepoints.describe(cp)

    private data class ContourProfile(
        val leftmostByRow: Map<Int, Int>,
        val rightmostByRow: Map<Int, Int>,
    )

    private fun effectiveUnrestoredAdvance(glyph: SolvedGlyph): Int {
        val seams = glyph.seamCorrections()
        var total = 0
        glyph.advances.forEachIndexed { idx, adv ->
            total += adv
            if (idx < glyph.advances.lastIndex) {
                total += seams[idx]
            }
        }
        return total
    }

    private fun rasterizeContour(glyph: SolvedGlyph): ContourProfile {
        val topY = GlyphMath.topOffset(glyph.ascent).toDouble()
        val scale = glyph.pixelScale.toDouble()
        val cellScreenW = (glyph.cellWidth * glyph.height) / glyph.cellHeight
        val leftmost = HashMap<Int, Int>()
        val rightmost = HashMap<Int, Int>()

        for (colIdx in 0 until glyph.columns) {
            val srcBaseX = colIdx * glyph.cellWidth
            val screenBaseX = colIdx * cellScreenW
            for (sy in 0 until glyph.cellHeight) {
                val y0 = floor(topY + sy * scale).toInt()
                val y1 = (ceil(topY + (sy + 1) * scale).toInt() - 1).coerceAtLeast(y0)
                for (sx in 0 until glyph.cellWidth) {
                    val alpha = glyph.texture.image.getRGB(srcBaseX + sx, sy) ushr 24
                    if (alpha == 0) continue
                    val x0 = screenBaseX + floor(sx * scale).toInt()
                    val x1 = (screenBaseX + ceil((sx + 1) * scale).toInt() - 1).coerceAtLeast(x0)
                    for (py in y0..y1) {
                        val curMin = leftmost[py]
                        if (curMin == null || x0 < curMin) leftmost[py] = x0
                        val curMax = rightmost[py]
                        if (curMax == null || x1 > curMax) rightmost[py] = x1
                    }
                }
            }
        }
        return ContourProfile(leftmost, rightmost)
    }
}
