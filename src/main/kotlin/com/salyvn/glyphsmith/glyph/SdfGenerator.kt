package com.salyvn.glyphsmith.glyph

import com.salyvn.glyphsmith.codepoint.Codepoints
import com.salyvn.glyphsmith.image.Texture
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class SdfEncodingMode(val cliName: String) {
    ALPHA_SDF("alpha-sdf"),
    GRAYSCALE_SDF("grayscale"),
    RECONSTRUCT_AA("reconstruct"),
    ;

    companion object {
        fun of(name: String): SdfEncodingMode = when (name.trim().lowercase()) {
            "alpha-sdf", "alpha", "sdf" -> ALPHA_SDF
            "grayscale", "gray", "channel-r", "rgb" -> GRAYSCALE_SDF
            "reconstruct", "aa", "reconstruct-aa" -> RECONSTRUCT_AA
            else -> throw IllegalArgumentException(
                "Unknown SDF mode '$name'. Expected one of: alpha-sdf, grayscale, reconstruct.",
            )
        }
    }
}

@Serializable
data class SdfGlyphMeta(
    val sourceWidthPx: Int,
    val sourceHeightPx: Int,
    val outputWidthPx: Int,
    val outputHeightPx: Int,
    val borderPadPx: Int,
    val spreadPx: Double,
    val scale: Double,
    val outlineWidthPx: Double,
    val encodingMode: String,
    val recommendedHeight: Int,
    val recommendedAscent: Int,
    val vanillaAdvancePx: Int,
    val minSignedDistancePx: Double,
    val maxSignedDistancePx: Double,
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

class SdfGlyphResult(
    val image: BufferedImage,
    val sdfGridWidth: Int,
    val sdfGridHeight: Int,
    val signedDistances: FloatArray,
    val voronoiRgb: IntArray,
    val spreadPx: Double,
    val borderPadPx: Int,
    val meta: SdfGlyphMeta,
) {
    val width: Int get() = image.width
    val height: Int get() = image.height

    fun signedDistanceAt(x: Int, y: Int): Float {
        require(x in 0 until sdfGridWidth && y in 0 until sdfGridHeight) {
            "Coordinates ($x, $y) outside SDF grid ${sdfGridWidth}x${sdfGridHeight}"
        }
        return signedDistances[y * sdfGridWidth + x]
    }

    fun normalizedAt(x: Int, y: Int): Float {
        val sd = signedDistanceAt(x, y).toDouble()
        return (0.5 + sd / (2.0 * spreadPx)).coerceIn(0.0, 1.0).toFloat()
    }

    fun reconstructAtScale(
        scale: Double,
        softness: Double = 0.75,
        outlineWidthPx: Double = 0.0,
        outlineArgb: Int = 0xFF000000.toInt(),
    ): BufferedImage = SdfGenerator.reconstructImage(
        sdfGridWidth = sdfGridWidth,
        sdfGridHeight = sdfGridHeight,
        signedDistances = signedDistances,
        voronoiRgb = voronoiRgb,
        scale = scale,
        softness = softness,
        outlineWidthPx = outlineWidthPx,
        outlineArgb = outlineArgb,
    )

    fun toPngBytes(): ByteArray {
        val baos = ByteArrayOutputStream()
        ImageIO.write(image, "PNG", baos)
        return baos.toByteArray()
    }

    fun toProviderJson(textureFile: String, codepoint: Int = 0xF0000): String {
        val escapedChar = String(Character.toChars(codepoint)).map { ch ->
            if (ch.code in 0x20..0x7E && ch != '"' && ch != '\\') ch.toString() else "\\u%04X".format(ch.code)
        }.joinToString("")
        return """
            {
              "type": "bitmap",
              "file": "$textureFile",
              "ascent": ${meta.recommendedAscent},
              "height": ${meta.recommendedHeight},
              "chars": [
                "$escapedChar"
              ]
            }
        """.trimIndent()
    }
}

/**
 * Exact O(W * H) 2D Euclidean Signed Distance Field (SDF) generator and multi-scale contour reconstructor.
 *
 * Uses Felzenszwalb and Huttenlocher's separable lower-envelope parabola distance transform with 2D Voronoi
 * nearest-seed tracking so exterior SDF skirt pixels inherit the nearest edge pixel's RGB color instead of
 * bleeding dark fringes under bilinear filtering, plus sub-pixel coverage contour refinement on anti-aliased
 * TrueType glyph boundaries.
 */
object SdfGenerator {
    private const val INF = 1.0e20

    data class Edt2DResult(
        val distSq: DoubleArray,
        val nearestIndex: IntArray,
    )

    fun generateFromImage(
        source: BufferedImage,
        spreadPx: Double = 6.0,
        padBorder: Boolean = true,
        baseAscent: Int = GlyphMath.BASELINE,
        mode: SdfEncodingMode = SdfEncodingMode.ALPHA_SDF,
        scale: Double = 1.0,
        outlineWidthPx: Double = 0.0,
        softness: Double = 0.75,
        outlineArgb: Int = 0xFF000000.toInt(),
    ): SdfGlyphResult {
        require(source.width > 0 && source.height > 0) { "Source image must have positive dimensions." }
        require(spreadPx > 0.0 && spreadPx.isFinite()) { "spreadPx must be positive and finite, got $spreadPx." }
        require(scale > 0.0 && scale.isFinite()) { "scale must be positive and finite, got $scale." }
        require(outlineWidthPx >= 0.0 && outlineWidthPx.isFinite()) {
            "outlineWidthPx must be non-negative and finite, got $outlineWidthPx."
        }

        val pad = if (padBorder) ceil(spreadPx).toInt().coerceAtLeast(1) else 0
        val baseAscentAdjusted = baseAscent + pad
        val prelimHeight = source.height + pad * 2
        val extraBottom = if (mode != SdfEncodingMode.RECONSTRUCT_AA && baseAscentAdjusted > prelimHeight) {
            baseAscentAdjusted - prelimHeight
        } else {
            0
        }

        val gridW = source.width + pad * 2
        val gridH = prelimHeight + extraBottom
        val total = gridW * gridH

        val alphaGrid = DoubleArray(total)
        val rgbGrid = IntArray(total) { 0x00FFFFFF }

        for (sy in 0 until source.height) {
            for (sx in 0 until source.width) {
                val argb = source.getRGB(sx, sy)
                val a = (argb ushr 24) and 0xFF
                val idx = (sy + pad) * gridW + (sx + pad)
                alphaGrid[idx] = a / 255.0
                rgbGrid[idx] = argb and 0x00FFFFFF
            }
        }

        var insideCount = 0
        var outsideCount = 0
        val insideMask = BooleanArray(total)
        val outsideMask = BooleanArray(total)
        for (i in 0 until total) {
            if (alphaGrid[i] >= 0.5) {
                insideMask[i] = true
                insideCount++
            } else {
                outsideMask[i] = true
                outsideCount++
            }
        }

        val signed = FloatArray(total)
        val voronoiRgb = IntArray(total) { 0x00FFFFFF }

        if (insideCount == 0) {
            val negCap = (-spreadPx * 2.0).toFloat()
            for (i in 0 until total) {
                signed[i] = negCap
                voronoiRgb[i] = 0x00FFFFFF
            }
        } else if (outsideCount == 0) {
            val posCap = (spreadPx * 2.0).toFloat()
            for (i in 0 until total) {
                signed[i] = posCap
                voronoiRgb[i] = rgbGrid[i]
            }
        } else {
            val toInside = edt2dWithSeeds(gridW, gridH, insideMask)
            val toOutside = edt2dWithSeeds(gridW, gridH, outsideMask)

            for (i in 0 until total) {
                val a = alphaGrid[i]
                val dIn = sqrt(toInside.distSq[i])
                val dOut = sqrt(toOutside.distSq[i])

                val sd = if (a > 0.004 && a < 0.996 && min(dIn, dOut) <= 1.0001) {
                    // Sub-pixel contour refinement for anti-aliased glyph boundaries; continuous at 0 and 1.
                    a - 0.5
                } else if (insideMask[i]) {
                    dOut - 0.5
                } else {
                    -(dIn - 0.5)
                }

                signed[i] = sd.toFloat()
                val seedIdx = toInside.nearestIndex[i]
                voronoiRgb[i] = if (insideMask[i]) rgbGrid[i] else rgbGrid[seedIdx]
            }
        }

        var minSd = Double.POSITIVE_INFINITY
        var maxSd = Double.NEGATIVE_INFINITY
        for (v in signed) {
            val dv = v.toDouble()
            if (dv < minSd) minSd = dv
            if (dv > maxSd) maxSd = dv
        }

        val renderedImage: BufferedImage
        val recommendedAscent: Int
        val recommendedHeight: Int

        when (mode) {
            SdfEncodingMode.ALPHA_SDF -> {
                renderedImage = BufferedImage(gridW, gridH, BufferedImage.TYPE_INT_ARGB)
                for (y in 0 until gridH) {
                    for (x in 0 until gridW) {
                        val idx = y * gridW + x
                        val u = (0.5 + signed[idx] / (2.0 * spreadPx)).coerceIn(0.0, 1.0)
                        val aByte = (u * 255.0).roundToInt().coerceIn(0, 255)
                        val argb = if (aByte == 0) 0 else ((aByte shl 24) or voronoiRgb[idx])
                        renderedImage.setRGB(x, y, argb)
                    }
                }
                recommendedAscent = baseAscentAdjusted
                recommendedHeight = max(gridH, recommendedAscent)
            }

            SdfEncodingMode.GRAYSCALE_SDF -> {
                renderedImage = BufferedImage(gridW, gridH, BufferedImage.TYPE_INT_ARGB)
                for (y in 0 until gridH) {
                    for (x in 0 until gridW) {
                        val idx = y * gridW + x
                        val u = (0.5 + signed[idx] / (2.0 * spreadPx)).coerceIn(0.0, 1.0)
                        val gByte = (u * 255.0).roundToInt().coerceIn(0, 255)
                        val argb = (0xFF shl 24) or (gByte shl 16) or (gByte shl 8) or gByte
                        renderedImage.setRGB(x, y, argb)
                    }
                }
                recommendedAscent = baseAscentAdjusted
                recommendedHeight = max(gridH, recommendedAscent)
            }

            SdfEncodingMode.RECONSTRUCT_AA -> {
                val rawReconstructed = reconstructImage(
                    sdfGridWidth = gridW,
                    sdfGridHeight = gridH,
                    signedDistances = signed,
                    voronoiRgb = voronoiRgb,
                    scale = scale,
                    softness = softness,
                    outlineWidthPx = outlineWidthPx,
                    outlineArgb = outlineArgb,
                )
                val scaledAscent = (baseAscentAdjusted * scale).roundToInt()
                if (scaledAscent > rawReconstructed.height) {
                    val grown = Texture(rawReconstructed, true).grown(scaledAscent - rawReconstructed.height, 0)
                    renderedImage = grown.image
                } else {
                    renderedImage = rawReconstructed
                }
                recommendedAscent = scaledAscent
                recommendedHeight = max(renderedImage.height, recommendedAscent)
            }
        }

        val tex = Texture(renderedImage, true)
        val pScale = GlyphMath.pixelScale(recommendedHeight, renderedImage.height)
        val vanillaAdvance = GlyphMath.advance(tex.inkWidth(0, 0, renderedImage.width, renderedImage.height), pScale)

        val meta = SdfGlyphMeta(
            sourceWidthPx = source.width,
            sourceHeightPx = source.height,
            outputWidthPx = renderedImage.width,
            outputHeightPx = renderedImage.height,
            borderPadPx = pad,
            spreadPx = spreadPx,
            scale = scale,
            outlineWidthPx = outlineWidthPx,
            encodingMode = mode.cliName,
            recommendedHeight = recommendedHeight,
            recommendedAscent = recommendedAscent,
            vanillaAdvancePx = vanillaAdvance,
            minSignedDistancePx = (minSd * 1000.0).roundToInt() / 1000.0,
            maxSignedDistancePx = (maxSd * 1000.0).roundToInt() / 1000.0,
        )

        return SdfGlyphResult(
            image = renderedImage,
            sdfGridWidth = gridW,
            sdfGridHeight = gridH,
            signedDistances = signed,
            voronoiRgb = voronoiRgb,
            spreadPx = spreadPx,
            borderPadPx = pad,
            meta = meta,
        )
    }

    fun generateFromFont(
        fontPath: Path,
        codepoint: Int,
        sizePt: Double = 48.0,
        spreadPx: Double = 6.0,
        mode: SdfEncodingMode = SdfEncodingMode.ALPHA_SDF,
        scale: Double = 1.0,
        outlineWidthPx: Double = 0.0,
        softness: Double = 0.75,
        outlineArgb: Int = 0xFF000000.toInt(),
    ): SdfGlyphResult {
        require(Codepoints.isUsable(codepoint) || Character.isValidCodePoint(codepoint)) {
            "Invalid Unicode codepoint: $codepoint"
        }
        require(sizePt > 0.0 && sizePt.isFinite()) { "sizePt must be positive, got $sizePt." }

        val awtFont = Font.createFont(Font.TRUETYPE_FONT, fontPath.toFile()).deriveFont(sizePt.toFloat())
        val text = String(Character.toChars(codepoint))
        val frc = FontRenderContext(AffineTransform(), true, true)
        val gv = awtFont.createGlyphVector(frc, text)
        val visual = gv.visualBounds

        val margin = 2
        val srcW = max(4, ceil(visual.width).toInt() + margin * 2)
        val srcH = max(4, ceil(visual.height).toInt() + margin * 2)
        val drawX = (-floor(visual.x).toInt()) + margin
        val drawY = (-floor(visual.y).toInt()) + margin

        val rendered = BufferedImage(srcW, srcH, BufferedImage.TYPE_INT_ARGB)
        val g = rendered.createGraphics()
        try {
            g.color = Color(0, 0, 0, 0)
            g.fillRect(0, 0, srcW, srcH)
            g.color = Color.WHITE
            g.font = awtFont
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
            g.drawString(text, drawX, drawY)
        } finally {
            g.dispose()
        }

        return generateFromImage(
            source = rendered,
            spreadPx = spreadPx,
            padBorder = true,
            baseAscent = drawY.coerceAtLeast(1),
            mode = mode,
            scale = scale,
            outlineWidthPx = outlineWidthPx,
            softness = softness,
            outlineArgb = outlineArgb,
        )
    }

    /**
     * Exact separable 2-pass Felzenszwalb-Huttenlocher 2D squared Euclidean Distance Transform in O(W * H)
     * with exact 2D Voronoi nearest-seed pixel index propagation.
     */
    fun edt2dWithSeeds(width: Int, height: Int, seeds: BooleanArray): Edt2DResult {
        val total = width * height
        val colDistSq = DoubleArray(total)
        val colBestY = IntArray(total) { -1 }

        val colF = DoubleArray(height)
        val colD = DoubleArray(height)
        val colArg = IntArray(height)
        val vBufY = IntArray(height)
        val zBufY = DoubleArray(height + 1)

        // Pass 1: exact 1D EDT along columns (Y axis)
        for (x in 0 until width) {
            var hasSeed = false
            for (y in 0 until height) {
                if (seeds[y * width + x]) {
                    colF[y] = 0.0
                    hasSeed = true
                } else {
                    colF[y] = INF
                }
            }
            if (!hasSeed) {
                for (y in 0 until height) {
                    colDistSq[y * width + x] = INF
                    colBestY[y * width + x] = -1
                }
            } else {
                edt1dFinite(colF, height, colD, colArg, vBufY, zBufY)
                for (y in 0 until height) {
                    colDistSq[y * width + x] = colD[y]
                    colBestY[y * width + x] = colArg[y]
                }
            }
        }

        // Pass 2: exact 1D EDT along rows (X axis), resolving 2D seed index (bestX, colBestY[bestX, y])
        val outDistSq = DoubleArray(total)
        val outSeedIdx = IntArray(total)

        val rowF = DoubleArray(width)
        val rowD = DoubleArray(width)
        val rowArg = IntArray(width)
        val vBufX = IntArray(width)
        val zBufX = DoubleArray(width + 1)

        for (y in 0 until height) {
            val rowOffset = y * width
            var hasFinite = false
            for (x in 0 until width) {
                val d = colDistSq[rowOffset + x]
                rowF[x] = d
                if (d < INF * 0.5) hasFinite = true
            }
            if (!hasFinite) {
                for (x in 0 until width) {
                    outDistSq[rowOffset + x] = INF
                    outSeedIdx[rowOffset + x] = 0
                }
            } else {
                edt1dFinite(rowF, width, rowD, rowArg, vBufX, zBufX)
                for (x in 0 until width) {
                    outDistSq[rowOffset + x] = rowD[x]
                    val bx = rowArg[x]
                    val by = colBestY[y * width + bx]
                    outSeedIdx[rowOffset + x] = by * width + bx
                }
            }
        }

        return Edt2DResult(outDistSq, outSeedIdx)
    }

    private fun edt1dFinite(
        f: DoubleArray,
        n: Int,
        outD: DoubleArray,
        outArg: IntArray,
        v: IntArray,
        z: DoubleArray,
    ) {
        var k = -1
        for (q in 0 until n) {
            val fq = f[q]
            if (fq >= INF * 0.5) continue
            if (k < 0) {
                k = 0
                v[0] = q
                z[0] = -Double.MAX_VALUE
                z[1] = Double.MAX_VALUE
                continue
            }
            val qSq = q.toDouble() * q.toDouble()
            var s: Double
            while (true) {
                val vk = v[k]
                val vkSq = vk.toDouble() * vk.toDouble()
                s = ((fq + qSq) - (f[vk] + vkSq)) / (2.0 * (q - vk))
                if (k > 0 && s <= z[k]) {
                    k--
                } else {
                    break
                }
            }
            k++
            v[k] = q
            z[k] = s
            z[k + 1] = Double.MAX_VALUE
        }

        var cur = 0
        for (p in 0 until n) {
            while (cur < k && z[cur + 1] < p.toDouble()) {
                cur++
            }
            val bestQ = v[cur]
            val dx = (p - bestQ).toDouble()
            outD[p] = dx * dx + f[bestQ]
            outArg[p] = bestQ
        }
    }

    internal fun reconstructImage(
        sdfGridWidth: Int,
        sdfGridHeight: Int,
        signedDistances: FloatArray,
        voronoiRgb: IntArray,
        scale: Double,
        softness: Double,
        outlineWidthPx: Double,
        outlineArgb: Int,
    ): BufferedImage {
        val outW = max(1, (sdfGridWidth * scale).roundToInt())
        val outH = max(1, (sdfGridHeight * scale).roundToInt())
        val out = BufferedImage(outW, outH, BufferedImage.TYPE_INT_ARGB)

        val aaSrc = (softness / scale.coerceAtLeast(1e-4)).coerceAtLeast(0.04)
        val outlineSrc = (outlineWidthPx / scale.coerceAtLeast(1e-4)).coerceAtLeast(0.0)
        val outA = ((outlineArgb ushr 24) and 0xFF) / 255.0
        val outR = (outlineArgb ushr 16) and 0xFF
        val outG = (outlineArgb ushr 8) and 0xFF
        val outB = outlineArgb and 0xFF

        for (oy in 0 until outH) {
            val gy = ((oy + 0.5) / scale - 0.5).coerceIn(0.0, (sdfGridHeight - 1).toDouble())
            val y0 = floor(gy).toInt().coerceIn(0, sdfGridHeight - 1)
            val y1 = min(sdfGridHeight - 1, y0 + 1)
            val ty = gy - y0

            for (ox in 0 until outW) {
                val gx = ((ox + 0.5) / scale - 0.5).coerceIn(0.0, (sdfGridWidth - 1).toDouble())
                val x0 = floor(gx).toInt().coerceIn(0, sdfGridWidth - 1)
                val x1 = min(sdfGridWidth - 1, x0 + 1)
                val tx = gx - x0

                val d00 = signedDistances[y0 * sdfGridWidth + x0].toDouble()
                val d10 = signedDistances[y0 * sdfGridWidth + x1].toDouble()
                val d01 = signedDistances[y1 * sdfGridWidth + x0].toDouble()
                val d11 = signedDistances[y1 * sdfGridWidth + x1].toDouble()
                val sd = (d00 * (1.0 - tx) + d10 * tx) * (1.0 - ty) + (d01 * (1.0 - tx) + d11 * tx) * ty

                val nearX = gx.roundToInt().coerceIn(0, sdfGridWidth - 1)
                val nearY = gy.roundToInt().coerceIn(0, sdfGridHeight - 1)
                val fillRgb = voronoiRgb[nearY * sdfGridWidth + nearX]
                val fillR = (fillRgb ushr 16) and 0xFF
                val fillG = (fillRgb ushr 8) and 0xFF
                val fillB = fillRgb and 0xFF

                val fillCov = smoothstep(-aaSrc, aaSrc, sd)
                if (outlineSrc <= 0.0 || outA <= 0.0) {
                    val aInt = (fillCov * 255.0).roundToInt().coerceIn(0, 255)
                    out.setRGB(ox, oy, if (aInt == 0) 0 else ((aInt shl 24) or fillRgb))
                } else {
                    val outerCov = smoothstep(-(outlineSrc + aaSrc), -(outlineSrc - aaSrc), sd)
                    val bgA = outerCov * outA
                    val finalA = fillCov + bgA * (1.0 - fillCov)
                    if (finalA <= 1.0 / 512.0) {
                        out.setRGB(ox, oy, 0)
                    } else {
                        val r = ((fillR * fillCov + outR * bgA * (1.0 - fillCov)) / finalA).roundToInt().coerceIn(0, 255)
                        val g = ((fillG * fillCov + outG * bgA * (1.0 - fillCov)) / finalA).roundToInt().coerceIn(0, 255)
                        val b = ((fillB * fillCov + outB * bgA * (1.0 - fillCov)) / finalA).roundToInt().coerceIn(0, 255)
                        val aInt = (finalA * 255.0).roundToInt().coerceIn(0, 255)
                        out.setRGB(ox, oy, (aInt shl 24) or (r shl 16) or (g shl 8) or b)
                    }
                }
            }
        }
        return out
    }

    private fun smoothstep(edge0: Double, edge1: Double, x: Double): Double {
        if (edge1 <= edge0) return if (x >= edge0) 1.0 else 0.0
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }
}
