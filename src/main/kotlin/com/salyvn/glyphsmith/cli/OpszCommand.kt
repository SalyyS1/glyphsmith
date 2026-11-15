package com.salyvn.glyphsmith.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import com.salyvn.glyphsmith.ttf.OpenTypeFont
import com.salyvn.glyphsmith.ttf.OpticalSizeCompiler

/**
 * Compiles optical size (opsz) axis coordinates and recommended multi-size provider configs for OpenType variable fonts.
 */
class OpszCommand : GlyphsmithCommand(name = "opsz") {

    override fun help(context: Context) =
        "Compile optical size (opsz) axis alignments and multi-size provider declarations for variable fonts."

    private val fontFile by argument("font", help = "Path to OpenType/TrueType variable font binary").path()

    private val json by option("--json", help = "Emit machine-readable JSON compilation report").flag()

    override fun execute() {
        val parsed = OpenTypeFont.read(fontFile)
        val result = OpticalSizeCompiler.compile(parsed, fontFilePath = fontFile.toString())

        if (json) {
            echo(result.toJsonString())
            return
        }

        echo("== opsz ${fontFile.fileName} (${result.familyName})")
        if (!result.hasOpszAxis) {
            echo("   warning      No 'opsz' variation axis found in font.")
            result.warnings.forEach { echo("   note         $it") }
            return
        }

        echo("   axis         opsz range ${result.opszRangeMin} .. ${result.opszRangeMax}, default=${result.opszDefault} pt")
        echo("   contexts     Compiled ${result.mappings.size} standard Minecraft UI font contexts:")

        result.mappings.forEach { m ->
            val clampNote = if (m.isClamped) " [clamped]" else ""
            echo(
                "      context '${m.context}' (target ${m.requestedSizePt} pt): opsz=${m.clampedOpsz}$clampNote, " +
                    "norm=${m.normalizedCoord}, avar=${m.avarWarpedCoord}, strokeRatio=${m.strokeScaleRatio}",
            )
        }

        echo("\n   recommended provider for chat (assets/<ns>/font/default.json):")
        val chatMapping = result.mappings.find { it.context == "chat" } ?: result.mappings.first()
        echo(chatMapping.recommendedProviderJson)
    }
}
