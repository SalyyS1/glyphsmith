package com.salyvn.glyphsmith.ttf

import com.salyvn.glyphsmith.audit.FindingSeverity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path

class OpticalSizeCompilerTest {

    private fun buildVariableFontWithOpsz(
        minOpsz: Int = 8,
        defOpsz: Int = 14,
        maxOpsz: Int = 72,
    ): ByteArray {
        val tables = LinkedHashMap<String, ByteArray>()
        tables["head"] = SfntFixtures.buildHead(unitsPerEm = 1000)
        tables["maxp"] = SfntFixtures.buildMaxp(numGlyphs = 4)
        tables["hhea"] = SfntFixtures.buildHhea(ascent = 800, descent = -200, lineGap = 50, numHMetrics = 4)
        tables["hmtx"] = SfntFixtures.buildHmtx(listOf(500, 600, 600, 600))
        tables["name"] = SfntFixtures.buildName(
            listOf(
                1 to "Test Opsz Variable",
                2 to "Regular",
                4 to "Test Opsz Variable Regular",
                256 to "Optical Size",
                257 to "Weight",
            ),
        )
        tables["cmap"] = SfntFixtures.buildCmapFormat12(listOf('A'.code, 'B'.code, 'C'.code))

        val fvarOut = ByteArrayOutputStream()
        val dos = DataOutputStream(fvarOut)
        dos.writeShort(1)
        dos.writeShort(0)
        dos.writeShort(16)
        dos.writeShort(2)
        dos.writeShort(2) // 2 axes: opsz, wght
        dos.writeShort(20)
        dos.writeShort(0)
        dos.writeShort(0)

        // Axis 0: opsz
        dos.writeBytes("opsz")
        dos.writeInt(minOpsz shl 16)
        dos.writeInt(defOpsz shl 16)
        dos.writeInt(maxOpsz shl 16)
        dos.writeShort(0)
        dos.writeShort(256)

        // Axis 1: wght
        dos.writeBytes("wght")
        dos.writeInt(100 shl 16)
        dos.writeInt(400 shl 16)
        dos.writeInt(900 shl 16)
        dos.writeShort(0)
        dos.writeShort(257)

        tables["fvar"] = fvarOut.toByteArray()
        tables["gvar"] = ByteArray(128)
        return SfntFixtures.assembleSfnt(0x00010000, tables)
    }

    @Test
    fun `compiles optical size mappings across all Minecraft UI contexts`(@TempDir tempDir: Path) {
        val fontBytes = buildVariableFontWithOpsz(minOpsz = 8, defOpsz = 14, maxOpsz = 72)
        val fontPath = tempDir.resolve("opsz_test.ttf")
        Files.write(fontPath, fontBytes)

        val font = OpenTypeFont.read(fontPath)
        val result = OpticalSizeCompiler.compile(font, fontFilePath = "minecraft:opsz_test.ttf")

        assertTrue(result.hasOpszAxis)
        assertEquals(8.0, result.opszRangeMin)
        assertEquals(14.0, result.opszDefault)
        assertEquals(72.0, result.opszRangeMax)
        assertEquals(6, result.mappings.size)

        // Chat: 9.0pt
        val chat = result.mappings.first { it.context == "chat" }
        assertEquals(9.0, chat.requestedSizePt)
        assertEquals(9.0, chat.clampedOpsz)
        assertFalse(chat.isClamped)
        assertTrue(chat.normalizedCoord < 0.0) // Below default 14.0
        assertTrue(chat.strokeScaleRatio < 1.0)
        assertTrue(chat.recommendedProviderJson.contains("\"size\": 9.0"))

        // Hotbar: 8.0pt (matches min boundary)
        val hotbar = result.mappings.first { it.context == "hotbar" }
        assertEquals(8.0, hotbar.clampedOpsz)
        assertEquals(-1.0, hotbar.normalizedCoord)

        // Title: 24.0pt
        val title = result.mappings.first { it.context == "title" }
        assertEquals(24.0, title.clampedOpsz)
        assertTrue(title.normalizedCoord > 0.0) // Above default 14.0
        assertTrue(title.strokeScaleRatio > 1.0)
    }

    @Test
    fun `clamps target sizes exceeding axis bounds`(@TempDir tempDir: Path) {
        // Narrow optical size axis [10..18, default=12]
        val fontBytes = buildVariableFontWithOpsz(minOpsz = 10, defOpsz = 12, maxOpsz = 18)
        val fontPath = tempDir.resolve("narrow_opsz.ttf")
        Files.write(fontPath, fontBytes)

        val font = OpenTypeFont.read(fontPath)
        val result = OpticalSizeCompiler.compile(font)

        val hotbar = result.mappings.first { it.context == "hotbar" } // 8.0pt < 10.0
        assertTrue(hotbar.isClamped)
        assertEquals(10.0, hotbar.clampedOpsz)

        val title = result.mappings.first { it.context == "title" } // 24.0pt > 18.0
        assertTrue(title.isClamped)
        assertEquals(18.0, title.clampedOpsz)
    }

    @Test
    fun `detects opsz mismatch when provider size deviates from default opsz`() {
        val axis = VariationAxis(
            tag = "opsz",
            name = "Optical Size",
            minValue = 8.0,
            defaultValue = 72.0, // Display font pinned at 72pt default
            maxValue = 72.0,
            hidden = false,
        )

        // Using 72pt display font at 11pt inventory size
        val finding = OpticalSizeCompiler.checkOpszMismatch(
            fontId = "minecraft:default",
            providerIndex = 0,
            providerSize = 11.0,
            opszAxis = axis,
            fontFile = "display_font.ttf",
            packLabel = "test_pack",
            filePath = "assets/minecraft/font/default.json",
        )

        assertNotNull(finding)
        assertEquals("TTF_VARIABLE_OPSZ_MISMATCH", finding?.rule)
        assertEquals(FindingSeverity.WARNING, finding?.severity)
        assertTrue(finding?.message?.contains("default 72.0") == true)

        // Size matching default opsz produces no warning
        val cleanFinding = OpticalSizeCompiler.checkOpszMismatch(
            fontId = "minecraft:default",
            providerIndex = 0,
            providerSize = 72.0,
            opszAxis = axis,
            fontFile = "display_font.ttf",
            packLabel = "test_pack",
            filePath = "assets/minecraft/font/default.json",
        )
        assertNull(cleanFinding)
    }
}
