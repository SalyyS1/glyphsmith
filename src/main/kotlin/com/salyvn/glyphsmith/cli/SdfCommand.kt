package com.salyvn.glyphsmith.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import com.salyvn.glyphsmith.codepoint.Codepoints
import com.salyvn.glyphsmith.glyph.GlyphMath
import com.salyvn.glyphsmith.glyph.SdfEncodingMode
import com.salyvn.glyphsmith.glyph.SdfGenerator
import com.salyvn.glyphsmith.image.Texture
import java.nio.file.Files
import kotlin.io.path.extension

/**
 * Generates an exact O(W * H) Felzenszwalb-Huttenlocher Euclidean Signed Distance Field (SDF) texture
 * or analytically reconstructed multi-scale anti-aliased glyph from a PNG sprite or TrueType/OpenType font.
 */
class SdfCommand : GlyphsmithCommand(name = "sdf") {

    override fun help(context: Context) =
        "Generate an exact 2D Signed Distance Field (SDF) glyph texture or multi-scale AA sprite from PNG or TTF."

    private val inputFile by argument(
        "input",
        help = "Path to source PNG sprite or TrueType/OpenType font (.ttf/.otf)",
    ).path()

    private val codepointOpt by option(
        "-c",
        "--codepoint",
        help = "Codepoint to render when input is a TTF/OTF font (e.g. A, U+0041, U+F0016)",
    )

    private val sizePt by option("--size", help = "Font render size in pt when input is TTF/OTF (default: 48.0)")
        .double()
        .default(48.0)

    private val spreadPx by option("--spread", help = "SDF Euclidean falloff radius in source pixels (default: 6.0)")
        .double()
        .default(6.0)

    private val modeName by option(
        "--mode",
        help = "Output mode: alpha-sdf (Voronoi RGB + alpha distance), grayscale (R=G=B=sdf), or reconstruct (crisp AA)",
    ).default("alpha-sdf")

    private val scale by option("--scale", help = "Output scale factor when --mode reconstruct is used (default: 1.0)")
        .double()
        .default(1.0)

    private val outlinePx by option(
        "--outline",
        help = "Optional vector outline width in output pixels when --mode reconstruct is used (default: 0.0)",
    ).double().default(0.0)

    private val baseAscent by option("--ascent", help = "Base ascent of source PNG before border padding (default: 7)")
        .int()
        .default(GlyphMath.BASELINE)

    private val noPad by option("--no-pad", help = "Do not add transparent spread padding around source PNG").flag()

    private val outPath by option("--out", help = "Output PNG file path").path()

    private val json by option("--json", help = "Emit structured JSON report").flag()

    override fun execute() {
        val mode = SdfEncodingMode.of(modeName)
        val ext = inputFile.extension.lowercase()
        val isFont = ext in setOf("ttf", "otf", "ttc") || codepointOpt != null

        val cpForProvider: Int
        val result = if (isFont) {
            val rawCp = codepointOpt
                ?: throw IllegalArgumentException("Font input '$inputFile' requires --codepoint (for example --codepoint A or --codepoint U+0041).")
            val cp = parseCodepointArg(rawCp)
            cpForProvider = cp
            SdfGenerator.generateFromFont(
                fontPath = inputFile,
                codepoint = cp,
                sizePt = sizePt,
                spreadPx = spreadPx,
                mode = mode,
                scale = scale,
                outlineWidthPx = outlinePx,
            )
        } else {
            cpForProvider = 0xF0000
            val tex = Texture.read(inputFile)
            SdfGenerator.generateFromImage(
                source = tex.image,
                spreadPx = spreadPx,
                padBorder = !noPad,
                baseAscent = baseAscent,
                mode = mode,
                scale = scale,
                outlineWidthPx = outlinePx,
            )
        }

        val defaultSuffix = if (mode == SdfEncodingMode.RECONSTRUCT_AA) "_aa.png" else "_sdf.png"
        val stem = inputFile.fileName.toString().substringBeforeLast('.')
        val targetOut = outPath ?: inputFile.toAbsolutePath().normalize().parent?.resolve("$stem$defaultSuffix")
        if (targetOut != null) {
            Files.createDirectories(targetOut.toAbsolutePath().normalize().parent)
            Files.write(targetOut, result.toPngBytes())
        }

        if (json) {
            echo(result.meta.toJsonString())
            return
        }

        val m = result.meta
        echo("== sdf ${inputFile.fileName} (${m.encodingMode})")
        echo("   source       ${m.sourceWidthPx}x${m.sourceHeightPx}px (border pad: ${m.borderPadPx}px, spread: ${m.spreadPx}px)")
        echo("   output       ${m.outputWidthPx}x${m.outputHeightPx}px (distance range: [${m.minSignedDistancePx}, ${m.maxSignedDistancePx}]px)")
        echo("   provider     height=${m.recommendedHeight}, ascent=${m.recommendedAscent}, vanilla advance=${m.vanillaAdvancePx}px")
        if (targetOut != null) {
            echo("   wrote        $targetOut")
        }
        echo("\n   suggested bitmap provider:")
        echo(result.toProviderJson(targetOut?.fileName?.toString() ?: "${stem}_sdf.png", cpForProvider))
    }

    private fun parseCodepointArg(raw: String): Int {
        val s = raw.trim()
        if (s.length == 1) return s[0].code
        if (s.length == 2 && Character.isSurrogatePair(s[0], s[1])) return Character.toCodePoint(s[0], s[1])
        return Codepoints.parse(s)
    }
}
