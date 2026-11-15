package com.salyvn.glyphsmith.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import com.salyvn.glyphsmith.glyph.SubpixelRasterizer
import com.salyvn.glyphsmith.glyph.SubpixelSamplingMode
import java.nio.file.Files

/**
 * Rasterizes a glyph with LCD sub-pixel RGB stripes and gamma correction into a Minecraft bitmap texture.
 */
class RasterizeCommand : GlyphsmithCommand(name = "rasterize") {

    override fun help(context: Context) =
        "Rasterize a TrueType glyph with horizontal 3x LCD sub-pixel anti-aliasing and gamma correction into a PNG texture."

    private val fontFile by argument("font", help = "Path to OpenType/TrueType font binary").path()

    private val codepointArg by argument("codepoint", help = "Codepoint as hex (0x41, U+0041) or single character")

    private val size by option("--size", help = "Render size in pt/pixels (default: 11.0)")
        .double()
        .default(11.0)

    private val gamma by option("--gamma", help = "Gamma correction exponent (default: 2.2)")
        .double()
        .default(2.2)

    private val modeName by option("--mode", help = "Sampling mode: subpixel-3x (default), ssaa-4x, or mono")
        .default("subpixel-3x")

    private val outPath by option("--out", help = "Output PNG texture file path").path()

    private val json by option("--json", help = "Emit JSON metadata and provider snippet").flag()

    override fun execute() {
        val cp = parseCodepoint(codepointArg)
        val mode = when (modeName.lowercase()) {
            "mono", "monochrome" -> SubpixelSamplingMode.MONOCHROME
            "ssaa", "ssaa-4x", "grayscale" -> SubpixelSamplingMode.GRAYSCALE_SSAA_4X
            else -> SubpixelSamplingMode.SUBPIXEL_HORIZONTAL_3X
        }

        val rasterized = SubpixelRasterizer.rasterizeFont(
            fontPath = fontFile,
            codepoint = cp,
            sizePt = size,
            mode = mode,
            gamma = gamma,
        )

        val targetOut = outPath ?: fontFile.parent?.resolve("${fontFile.fileName.toString().substringBeforeLast('.')}_u${cp.toString(16).uppercase()}.png")
        if (targetOut != null) {
            Files.write(targetOut, rasterized.toPngBytes())
        }

        if (json) {
            val meta = rasterized.meta
            echo("""
                {
                  "codepointHex": "${meta.codepointHex}",
                  "character": "${meta.character}",
                  "widthPx": ${meta.widthPx},
                  "heightPx": ${meta.heightPx},
                  "advancePx": ${meta.advancePx},
                  "ascentPx": ${meta.ascentPx},
                  "samplingMode": "${meta.samplingMode}",
                  "gamma": ${meta.gamma},
                  "outPng": "${targetOut ?: "<none>"}"
                }
            """.trimIndent())
            return
        }

        echo("== rasterize ${fontFile.fileName}")
        echo("   codepoint    U+${cp.toString(16).uppercase().padStart(4, '0')} ('${rasterized.character}')")
        echo("   mode         ${rasterized.samplingMode} (gamma=$gamma)")
        echo("   metrics      size=${size}pt -> width=${rasterized.width}px, height=${rasterized.height}px, advance=${rasterized.advance}px, ascent=${rasterized.ascent}px")
        if (targetOut != null) {
            echo("   texture      Saved PNG texture to $targetOut")
        }
        echo("\n   suggested bitmap provider snippet:")
        echo(rasterized.toProviderJson(targetOut?.fileName?.toString() ?: "custom_glyph.png"))
    }

    private fun parseCodepoint(input: String): Int {
        val s = input.trim()
        if (s.startsWith("U+", ignoreCase = true) || s.startsWith("0x", ignoreCase = true)) {
            val hex = s.substring(2)
            return hex.toIntOrNull(16) ?: throw IllegalArgumentException("Invalid hex codepoint: $input")
        }
        if (s.length == 1) return s[0].code
        if (s.length == 2 && Character.isSurrogatePair(s[0], s[1])) return Character.toCodePoint(s[0], s[1])
        return s.toIntOrNull() ?: throw IllegalArgumentException("Could not parse codepoint from '$input'")
    }
}
