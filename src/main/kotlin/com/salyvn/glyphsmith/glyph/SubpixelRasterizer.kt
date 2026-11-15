package com.salyvn.glyphsmith.glyph

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

enum class SubpixelSamplingMode {
    MONOCHROME,
    GRAYSCALE_SSAA_4X,
    SUBPIXEL_HORIZONTAL_3X,
}

@Serializable
data class RasterizedGlyphMeta(
    val codepointHex: String,
    val character: String,
    val widthPx: Int,
    val heightPx: Int,
    val advancePx: Int,
    val ascentPx: Int,
    val samplingMode: String,
    val gamma: Double,
)

class RasterizedGlyph(
    val codepoint: Int,
    val character: String,
    val image: BufferedImage,
    val width: Int,
    val height: Int,
    val advance: Int,
    val ascent: Int,
    val samplingMode: SubpixelSamplingMode,
    val gamma: Double,
) {
    val meta: RasterizedGlyphMeta
        get() = RasterizedGlyphMeta(
            codepointHex = "U+${codepoint.toString(16).uppercase().padStart(4, '0')}",
            character = character,
            widthPx = width,
            heightPx = height,
            advancePx = advance,
            ascentPx = ascent,
            samplingMode = samplingMode.name,
            gamma = gamma,
        )

    fun toPngBytes(): ByteArray {
        val baos = ByteArrayOutputStream()
        ImageIO.write(image, "PNG", baos)
        return baos.toByteArray()
    }

    fun toProviderJson(textureFile: String): String {
        return """
            {
              "type": "bitmap",
              "file": "$textureFile",
              "ascent": $ascent,
              "height": $height,
              "chars": [
                "$character"
              ]
            }
        """.trimIndent()
    }
}

/**
 * Sub-pixel glyph rasterizer for Minecraft font engineering.
 *
 * Simulates FreeType and LCD sub-pixel rendering with horizontal 3x RGB stripes and gamma correction (default 2.2).
 * Prevents text contrast thinning on Minecraft's dark chatbox and aligns baseline geometry to standard
 * Minecraft font conventions (ascent=7, height=9+).
 */
object SubpixelRasterizer {

    fun rasterizeFont(
        fontPath: Path,
        codepoint: Int,
        sizePt: Double = 11.0,
        mode: SubpixelSamplingMode = SubpixelSamplingMode.SUBPIXEL_HORIZONTAL_3X,
        gamma: Double = 2.2,
        targetAscent: Int = 7,
    ): RasterizedGlyph {
        val awtFont = Font.createFont(Font.TRUETYPE_FONT, fontPath.toFile()).deriveFont(sizePt.toFloat())
        val charStr = String(Character.toChars(codepoint))

        val frc = FontRenderContext(AffineTransform(), true, true)
        val gv = awtFont.createGlyphVector(frc, charStr)
        val bounds = gv.visualBounds
        val logicalMetrics = gv.logicalBounds

        val inkWidth = max(1, bounds.width.roundToInt())
        val inkHeight = max(1, bounds.height.roundToInt())
        val advance = max(inkWidth + 1, gv.getGlyphMetrics(0).advanceX.roundToInt())

        // Minecraft baseline convention: ascent = 7, glyph sits on baseline
        val baselineY = targetAscent
        val totalHeight = max(targetAscent + 2, inkHeight + 2)
        val totalWidth = max(inkWidth + 1, advance)

        val image = when (mode) {
            SubpixelSamplingMode.MONOCHROME -> rasterizeMonochrome(awtFont, charStr, totalWidth, totalHeight, baselineY)
            SubpixelSamplingMode.GRAYSCALE_SSAA_4X -> rasterizeSsaa4x(awtFont, charStr, totalWidth, totalHeight, baselineY, gamma)
            SubpixelSamplingMode.SUBPIXEL_HORIZONTAL_3X -> rasterizeSubpixel3x(awtFont, charStr, totalWidth, totalHeight, baselineY, gamma)
        }

        return RasterizedGlyph(
            codepoint = codepoint,
            character = charStr,
            image = image,
            width = totalWidth,
            height = totalHeight,
            advance = advance,
            ascent = targetAscent,
            samplingMode = mode,
            gamma = gamma,
        )
    }

    private fun rasterizeMonochrome(
        font: Font,
        text: String,
        width: Int,
        height: Int,
        baselineY: Int,
    ): BufferedImage {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try {
            g.color = Color(0, 0, 0, 0)
            g.fillRect(0, 0, width, height)
            g.color = Color.WHITE
            g.font = font
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF)
            g.drawString(text, 0, baselineY)
        } finally {
            g.dispose()
        }
        return img
    }

    private fun rasterizeSsaa4x(
        font: Font,
        text: String,
        width: Int,
        height: Int,
        baselineY: Int,
        gamma: Double,
    ): BufferedImage {
        val scale = 4
        val hiRes = BufferedImage(width * scale, height * scale, BufferedImage.TYPE_INT_ARGB)
        val g = hiRes.createGraphics()
        try {
            g.color = Color(0, 0, 0, 0)
            g.fillRect(0, 0, width * scale, height * scale)
            g.color = Color.WHITE
            g.font = font.deriveFont(font.size2D * scale)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
            g.drawString(text, 0, baselineY * scale)
        } finally {
            g.dispose()
        }

        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val invGamma = 1.0 / gamma

        for (y in 0 until height) {
            for (x in 0 until width) {
                var alphaSum = 0.0
                for (dy in 0 until scale) {
                    for (dx in 0 until scale) {
                        val argb = hiRes.getRGB(x * scale + dx, y * scale + dy)
                        val a = (argb ushr 24) / 255.0
                        alphaSum += a.pow(gamma) // Linear accumulation
                    }
                }
                val avgLinear = alphaSum / (scale * scale)
                val correctedAlpha = avgLinear.pow(invGamma).coerceIn(0.0, 1.0)
                val aInt = (correctedAlpha * 255.0).roundToInt()
                val pixelArgb = (aInt shl 24) or (255 shl 16) or (255 shl 8) or 255
                out.setRGB(x, y, pixelArgb)
            }
        }
        return out
    }

    private fun rasterizeSubpixel3x(
        font: Font,
        text: String,
        width: Int,
        height: Int,
        baselineY: Int,
        gamma: Double,
    ): BufferedImage {
        // 3x horizontal sub-pixel sampling: render at 3x width
        val subpixelScale = 3
        val hiRes = BufferedImage(width * subpixelScale, height, BufferedImage.TYPE_INT_ARGB)
        val g = hiRes.createGraphics()
        try {
            g.color = Color(0, 0, 0, 0)
            g.fillRect(0, 0, width * subpixelScale, height)
            g.color = Color.WHITE
            // Stretch horizontally by 3x
            val at = AffineTransform.getScaleInstance(subpixelScale.toDouble(), 1.0)
            g.font = font.deriveFont(at)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
            g.drawString(text, 0, baselineY)
        } finally {
            g.dispose()
        }

        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val invGamma = 1.0 / gamma

        for (y in 0 until height) {
            for (x in 0 until width) {
                val rAlpha = ((hiRes.getRGB(x * subpixelScale, y) ushr 24) / 255.0).pow(gamma)
                val gAlpha = ((hiRes.getRGB(x * subpixelScale + 1, y) ushr 24) / 255.0).pow(gamma)
                val bAlpha = ((hiRes.getRGB(x * subpixelScale + 2, y) ushr 24) / 255.0).pow(gamma)

                val rOut = (rAlpha.pow(invGamma).coerceIn(0.0, 1.0) * 255.0).roundToInt()
                val gOut = (gAlpha.pow(invGamma).coerceIn(0.0, 1.0) * 255.0).roundToInt()
                val bOut = (bAlpha.pow(invGamma).coerceIn(0.0, 1.0) * 255.0).roundToInt()

                val masterAlpha = max(rOut, max(gOut, bOut))
                val pixelArgb = (masterAlpha shl 24) or (rOut shl 16) or (gOut shl 8) or bOut
                out.setRGB(x, y, pixelArgb)
            }
        }
        return out
    }
}
