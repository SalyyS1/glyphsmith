package com.salyvn.glyphsmith.glyph

import com.salyvn.glyphsmith.ttf.SfntFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SubpixelRasterizerTest {

    @Test
    fun `rasterizes glyph in subpixel 3x and ssaa 4x modes`(@TempDir tempDir: Path) {
        val fontBytes = SfntFixtures.buildStaticFont(
            codepoints = listOf('A'.code, 'B'.code, 'C'.code),
        )
        val fontPath = tempDir.resolve("test_raster.ttf")
        Files.write(fontPath, fontBytes)

        // Subpixel 3x
        val subpixelGlyph = SubpixelRasterizer.rasterizeFont(
            fontPath = fontPath,
            codepoint = 'A'.code,
            sizePt = 11.0,
            mode = SubpixelSamplingMode.SUBPIXEL_HORIZONTAL_3X,
            gamma = 2.2,
        )

        assertNotNull(subpixelGlyph)
        assertEquals("A", subpixelGlyph.character)
        assertEquals(7, subpixelGlyph.ascent) // Minecraft baseline
        assertTrue(subpixelGlyph.width > 0)
        assertTrue(subpixelGlyph.height >= 9)
        assertTrue(subpixelGlyph.advance > 0)
        assertEquals(SubpixelSamplingMode.SUBPIXEL_HORIZONTAL_3X, subpixelGlyph.samplingMode)
        assertEquals(2.2, subpixelGlyph.gamma)

        // PNG export
        val pngBytes = subpixelGlyph.toPngBytes()
        assertTrue(pngBytes.isNotEmpty())
        assertEquals(0x89.toByte(), pngBytes[0]) // PNG magic bytes
        assertEquals('P'.code.toByte(), pngBytes[1])
        assertEquals('N'.code.toByte(), pngBytes[2])
        assertEquals('G'.code.toByte(), pngBytes[3])

        // Provider JSON
        val providerJson = subpixelGlyph.toProviderJson("custom_a.png")
        assertTrue(providerJson.contains("\"type\": \"bitmap\""))
        assertTrue(providerJson.contains("\"file\": \"custom_a.png\""))
        assertTrue(providerJson.contains("\"ascent\": 7"))

        // SSAA 4x
        val ssaaGlyph = SubpixelRasterizer.rasterizeFont(
            fontPath = fontPath,
            codepoint = 'B'.code,
            sizePt = 14.0,
            mode = SubpixelSamplingMode.GRAYSCALE_SSAA_4X,
            gamma = 2.0,
        )
        assertNotNull(ssaaGlyph)
        assertEquals("B", ssaaGlyph.character)
        assertEquals(SubpixelSamplingMode.GRAYSCALE_SSAA_4X, ssaaGlyph.samplingMode)

        // Monochrome
        val monoGlyph = SubpixelRasterizer.rasterizeFont(
            fontPath = fontPath,
            codepoint = 'C'.code,
            sizePt = 11.0,
            mode = SubpixelSamplingMode.MONOCHROME,
        )
        assertNotNull(monoGlyph)
        assertEquals("C", monoGlyph.character)
    }
}
