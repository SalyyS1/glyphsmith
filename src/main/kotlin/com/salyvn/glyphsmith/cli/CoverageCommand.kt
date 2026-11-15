package com.salyvn.glyphsmith.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import com.salyvn.glyphsmith.codepoint.UnicodeCoverageValidator
import com.salyvn.glyphsmith.ttf.OpenTypeFont

/**
 * Validates Unicode 16.0 plane coverage and script completeness of a font or pack.
 */
class CoverageCommand : GlyphsmithCommand(name = "coverage") {

    override fun help(context: Context) =
        "Analyze Unicode 16.0 plane coverage (BMP, SMP, SIP, TIP, PUA) and script completeness for a font binary."

    private val fontFile by argument("font", help = "Path to OpenType/TrueType font binary").path()

    private val json by option("--json", help = "Emit machine-readable JSON coverage report").flag()

    override fun execute() {
        val parsed = OpenTypeFont.read(fontFile)
        val codepoints = parsed.codepointToGlyph.keys
        val report = UnicodeCoverageValidator.analyzeCoverage(codepoints)

        if (json) {
            echo(report.toJsonString())
            return
        }

        echo("== coverage ${fontFile.fileName} (${parsed.info.fullName})")
        echo("   total mapped ${report.totalMappedCodepoints} Unicode codepoints across ${report.planeCounts.size} plane(s)")
        report.planeCounts.toSortedMap().forEach { (plane, count) ->
            val label = when (plane) {
                0 -> "Plane 0 (BMP - Basic Multilingual Plane)"
                1 -> "Plane 1 (SMP - Supplementary Multilingual Plane)"
                2 -> "Plane 2 (SIP - Supplementary Ideographic Plane)"
                14 -> "Plane 14 (SSP - Special-purpose Plane)"
                15 -> "Plane 15 (Supplementary PUA-A)"
                16 -> "Plane 16 (Supplementary PUA-B)"
                else -> "Plane $plane"
            }
            echo("      plane $plane: $count codepoint(s) - $label")
        }

        echo("\n   standard Unicode 16.0 script blocks:")
        report.blockCoverages.filter { it.mappedCount > 0 }.forEach { block ->
            val sample = if (block.sampleMissing.isNotEmpty() && block.coveragePercent < 100.0) {
                " (missing e.g. ${block.sampleMissing.take(3).joinToString(", ")})"
            } else {
                ""
            }
            echo("      ${block.name.padEnd(28)} ${block.mappedCount}/${block.totalBlockChars} (${block.coveragePercent}%)$sample")
        }
    }
}
