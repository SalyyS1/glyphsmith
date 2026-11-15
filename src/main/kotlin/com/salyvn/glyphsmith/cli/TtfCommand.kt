package com.salyvn.glyphsmith.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.path
import com.salyvn.glyphsmith.glyph.KerningOptimizer
import com.salyvn.glyphsmith.glyph.OpenTypeKerningOptimization
import com.salyvn.glyphsmith.ttf.OpenTypeFont
import com.salyvn.glyphsmith.ttf.OpenTypeFontInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class TtfInspectionJson(
    val file: String,
    val font: OpenTypeFontInfo,
    val kerningOptimization: OpenTypeKerningOptimization,
)

/**
 * Extracts TrueType / OpenType SFNT metadata, `fvar` / `avar` / `STAT` variable font axes & named instances,
 * and quantized `kern` / `GPOS` pixel kerning tables at a target Minecraft `size`.
 */
class TtfCommand : GlyphsmithCommand(name = "ttf") {

    override fun help(context: Context) =
        "Inspect a TrueType or OpenType (.ttf/.otf) binary for variable font axes, cmap coverage, and pixel kerning."

    private val file by argument("font", help = "Path to a .ttf, .otf, or .ttc font binary").path()

    private val size by option("--size", help = "Minecraft ttf provider render size in pixels (default: 11.0)")
        .double()
        .default(11.0)

    private val json by option("--json", help = "Emit machine-readable JSON inspection report to stdout").flag()

    override fun execute() {
        val parsed = OpenTypeFont.read(file)
        val info = parsed.info
        val kernOpt = KerningOptimizer.optimizeOpenTypePairs(parsed, sizePx = size)

        if (json) {
            val out = FORMAT.encodeToString(
                TtfInspectionJson(
                    file = file.toString(),
                    font = info,
                    kerningOptimization = kernOpt,
                ),
            )
            echo(out)
            return
        }

        echo("== ttf $file")
        echo("   family       ${info.fullName} (${info.outlineFormat})")
        if (info.isCollection) {
            echo("   collection   TTC container with ${info.collectionFontCount} faces (face #0 inspected)")
        }
        echo("   metrics      ${info.unitsPerEm} upm, ascent ${info.ascent}, descent ${info.descent}, lineGap ${info.lineGap}")
        echo("   glyphs       ${info.numGlyphs} glyphs, ${info.mappedCodepointCount} mapped Unicode codepoints")
        echo("   tables       ${info.tables.joinToString(", ")}")

        if (info.isVariableFont) {
            echo("   variable     ${info.variationAxes.size} fvar axis/axes (${info.variationPayloadBytes} variation bytes)")
            info.variationAxes.forEach { ax ->
                val hiddenNote = if (ax.hidden) " [hidden]" else ""
                val avarNote = if (ax.avarSegmentPoints > 0) " (${ax.avarSegmentPoints} avar warp pts)" else ""
                echo("      axis ${ax.tag} (${ax.name}): ${ax.minValue} .. ${ax.maxValue}, default=${ax.defaultValue}$hiddenNote$avarNote")
            }
            if (info.namedInstances.isNotEmpty()) {
                val names = info.namedInstances.joinToString(", ") { inst ->
                    val coords = inst.coordinates.entries.joinToString(",") { "${it.key}=${it.value}" }
                    "${inst.name}[$coords]"
                }
                echo("   instances    $names")
            }
            echo("   warning      Minecraft FreeType renders only default fvar coordinates; subset a static instance for non-default weights.")
        } else {
            echo("   variable     no (static SFNT font)")
        }

        echo(
            "   kerning      ${kernOpt.rawPairCount} raw SFNT pairs -> ${kernOpt.activePixelPairCount} active at size=$size " +
                "(${kernOpt.prunedSubpixelCount} sub-pixel 0px pruned, ${kernOpt.residualPairCount} residual after sidebearing bias)",
        )
        kernOpt.pairs.take(8).forEach { pair ->
            echo("      pair '${pair.leftChar}' + '${pair.rightChar}' (${pair.source}): ${pair.designUnits}u -> ${pair.pixelDelta}px (residual ${pair.residualPixelDelta}px)")
        }
    }

    private companion object {
        val FORMAT = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
            encodeDefaults = true
        }
    }
}
