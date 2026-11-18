package com.salyvn.glyphsmith.glyph

import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import com.salyvn.glyphsmith.GlyphsmithCli
import com.salyvn.glyphsmith.cli.SdfCommand
import com.salyvn.glyphsmith.ttf.SfntFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import kotlin.io.path.exists
import kotlin.math.sqrt

class SdfGeneratorTest {

    @Test
    fun `edt2dWithSeeds matches exact brute-force Euclidean distance and Voronoi nearest seed`() {
        val w = 23
        val h = 19
        val rng = Random(42L)
        val seeds = BooleanArray(w * h) { rng.nextDouble() < 0.18 }
        seeds[10 * w + 12] = true

        val res = SdfGenerator.edt2dWithSeeds(w, h, seeds)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var bestSq = Double.POSITIVE_INFINITY
                for (sy in 0 until h) {
                    for (sx in 0 until w) {
                        if (!seeds[sy * w + sx]) continue
                        val dx = (x - sx).toDouble()
                        val dy = (y - sy).toDouble()
                        val d2 = dx * dx + dy * dy
                        if (d2 < bestSq) bestSq = d2
                    }
                }
                val idx = y * w + x
                assertEquals(bestSq, res.distSq[idx], 1e-9, "Mismatch at ($x, $y)")
                val nearIdx = res.nearestIndex[idx]
                assertTrue(seeds[nearIdx], "Nearest index $nearIdx must be a seed")
                val nx = nearIdx % w
                val ny = nearIdx / w
                val nearSq = ((x - nx) * (x - nx) + (y - ny) * (y - ny)).toDouble()
                assertEquals(bestSq, nearSq, 1e-9, "Voronoi seed distance must equal minimum distance")
            }
        }
    }

    @Test
    fun `computes exact 3-4-5 Pythagorean distance and dilates Voronoi RGB to prevent dark bilinear fringes`() {
        val src = BufferedImage(12, 12, BufferedImage.TYPE_INT_ARGB)
        val emeraldRgb = 0x10B981
        src.setRGB(2, 3, (0xFF shl 24) or emeraldRgb)

        val sdf = SdfGenerator.generateFromImage(
            source = src,
            spreadPx = 8.0,
            padBorder = false,
            baseAscent = 7,
            mode = SdfEncodingMode.ALPHA_SDF,
        )

        // At (2 + 3, 3 + 4) = (5, 7), Euclidean center distance to (2, 3) is sqrt(3^2 + 4^2) = 5.0,
        // so signed distance is -(5.0 - 0.5) = -4.5.
        assertEquals(-4.5f, sdf.signedDistanceAt(5, 7), 1e-4f)
        // At seed (2, 3), nearest outside neighbor is 1.0px away -> +(1.0 - 0.5) = +0.5.
        assertEquals(0.5f, sdf.signedDistanceAt(2, 3), 1e-4f)

        // Exterior pixel within spread radius must carry emerald RGB (0x10B981), never black (0x000000).
        val skirtArgb = sdf.image.getRGB(5, 7)
        val skirtAlpha = (skirtArgb ushr 24) and 0xFF
        assertTrue(skirtAlpha > 0, "Pixel at -4.5px within 8.0px spread must have non-zero SDF alpha")
        assertEquals(emeraldRgb, skirtArgb and 0x00FFFFFF, "Voronoi RGB dilation must preserve glyph edge color")
    }

    @Test
    fun `pads border by spread, lifts ascent to preserve baseline, and reconstructs multi-scale AA with outline`() {
        val src = BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB)
        for (y in 2..7) {
            for (x in 2..7) {
                src.setRGB(x, y, 0xFFFFD700.toInt())
            }
        }

        val alphaSdf = SdfGenerator.generateFromImage(
            source = src,
            spreadPx = 4.0,
            padBorder = true,
            baseAscent = 8,
            mode = SdfEncodingMode.ALPHA_SDF,
        )
        assertEquals(4, alphaSdf.borderPadPx)
        assertEquals(18, alphaSdf.width)
        assertEquals(18, alphaSdf.height)
        assertEquals(12, alphaSdf.meta.recommendedAscent, "4px top pad raises ascent from 8 to 12 to keep baseline fixed")
        assertTrue(alphaSdf.meta.recommendedAscent <= alphaSdf.meta.recommendedHeight)

        val reconstructed = SdfGenerator.generateFromImage(
            source = src,
            spreadPx = 4.0,
            padBorder = true,
            baseAscent = 8,
            mode = SdfEncodingMode.RECONSTRUCT_AA,
            scale = 2.0,
            outlineWidthPx = 2.0,
            outlineArgb = 0xFF112233.toInt(),
        )
        assertEquals(36, reconstructed.width)
        assertEquals(36, reconstructed.height)
        // Center of scaled box should retain gold fill color
        assertEquals(0xFFD700, reconstructed.image.getRGB(18, 18) and 0x00FFFFFF)
        // Far corner should remain transparent
        assertEquals(0, (reconstructed.image.getRGB(0, 0) ushr 24) and 0xFF)
    }

    @Test
    fun `handles empty and fully-opaque images without NaN and runs SdfCommand on PNG and TTF`(@TempDir dir: Path) {
        val empty = BufferedImage(6, 6, BufferedImage.TYPE_INT_ARGB)
        val emptyRes = SdfGenerator.generateFromImage(empty, spreadPx = 3.0, padBorder = false)
        assertFalse(emptyRes.signedDistances.any { it.isNaN() })

        val full = BufferedImage(6, 6, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 6) for (x in 0 until 6) full.setRGB(x, y, 0xFFFFFFFF.toInt())
        val fullRes = SdfGenerator.generateFromImage(
            full,
            spreadPx = 3.0,
            padBorder = false,
            baseAscent = 6,
            mode = SdfEncodingMode.GRAYSCALE_SDF,
        )
        assertFalse(fullRes.signedDistances.any { it.isNaN() })
        assertEquals(0xFFFFFFFF.toInt(), fullRes.image.getRGB(3, 3))

        val ttfFile = dir.resolve("test.ttf")
        Files.write(ttfFile, SfntFixtures.buildStaticFont(codepoints = listOf('A'.code, 'B'.code)))
        val outPng = dir.resolve("glyph_a_sdf.png")

        val cli = GlyphsmithCli().subcommands(SdfCommand())
        cli.parse(
            listOf(
                "sdf",
                ttfFile.toString(),
                "--codepoint",
                "U+0041",
                "--size",
                "32.0",
                "--spread",
                "4.0",
                "--out",
                outPng.toString(),
                "--json",
            ),
        )
        assertTrue(outPng.exists())
        assertTrue(Files.size(outPng) > 64)
    }
}
