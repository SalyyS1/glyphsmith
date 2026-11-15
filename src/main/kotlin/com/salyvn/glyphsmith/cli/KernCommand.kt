package com.salyvn.glyphsmith.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import com.salyvn.glyphsmith.glyph.KerningOptimizer
import com.salyvn.glyphsmith.glyph.Solver
import com.salyvn.glyphsmith.image.Texture

/**
 * Solves optical bitmap kerning between two glyph PNGs on Minecraft's baseline grid and compares standard
 * single-sign shift glyph count against signed-digit Non-Adjacent Form (NAF).
 */
class KernCommand : GlyphsmithCommand(name = "kern") {

    override fun help(context: Context) =
        "Compute optical pixel kerning and minimal shift-glyph cost between two bitmap glyph PNGs."

    private val leftImage by argument("left", help = "Left glyph PNG image").path()

    private val rightImage by argument("right", help = "Right glyph PNG image").path()

    private val targetGap by option("--target-gap", help = "Desired optical pixel gap between ink contours (default: 1)")
        .int()
        .default(1)

    private val leftHeight by option("--left-height", help = "Left glyph provider height").int()
    private val leftAscent by option("--left-ascent", help = "Left glyph provider ascent").int()
    private val rightHeight by option("--right-height", help = "Right glyph provider height").int()
    private val rightAscent by option("--right-ascent", help = "Right glyph provider ascent").int()

    private val shiftRange by option("--shifts", help = "Maximum power-of-two shift step (default: 1024)")
        .int()
        .default(1024)

    private val noDiagonal by option("--no-diagonal", help = "Allow corner-touching across adjacent scanlines").flag()

    private val json by option("--json", help = "Emit machine-readable JSON kerning result to stdout").flag()

    override fun execute() {
        val leftTex = Texture.read(leftImage)
        val rightTex = Texture.read(rightImage)

        val leftSolved = Solver.solve(
            texture = leftTex,
            placement = null,
            ascentOverride = leftAscent,
            heightOverride = leftHeight,
        )
        val rightSolved = Solver.solve(
            texture = rightTex,
            placement = null,
            ascentOverride = rightAscent,
            heightOverride = rightHeight,
        )

        val pair = KerningOptimizer.solveBitmapPair(
            leftId = leftImage.fileName.toString(),
            left = leftSolved,
            rightId = rightImage.fileName.toString(),
            right = rightSolved,
            targetGapPx = targetGap,
            diagonalClearance = !noDiagonal,
            shiftRange = shiftRange,
        )

        if (json) {
            echo(pair.toJsonString())
            return
        }

        echo("== kern ${pair.leftId} -> ${pair.rightId}")
        echo("   left advance   ${pair.leftAdvancePx}px")
        echo("   natural gap    ${pair.naturalGapPx?.let { "${it}px" } ?: "disjoint vertical rows"}")
        echo("   target gap     ${pair.targetGapPx}px")
        echo("   kerning shift  ${if (pair.kerningPx >= 0) "+${pair.kerningPx}" else "${pair.kerningPx}"}px")
        echo("   shift glyphs   ${pair.minimalShiftGlyphs} minimal NAF (vs ${pair.standardShiftGlyphs} standard)")
    }
}
