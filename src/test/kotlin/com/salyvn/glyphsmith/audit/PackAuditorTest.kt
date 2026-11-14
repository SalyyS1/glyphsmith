package com.salyvn.glyphsmith.audit

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import com.salyvn.glyphsmith.GlyphsmithCli
import com.salyvn.glyphsmith.cli.AuditCommand
import com.salyvn.glyphsmith.cli.BuildCommand
import com.salyvn.glyphsmith.cli.InitCommand
import com.salyvn.glyphsmith.cli.PlanCommand
import com.salyvn.glyphsmith.image.Texture
import com.salyvn.glyphsmith.pack.PackWriter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class PackAuditorTest {

    @Test
    fun `audits a built glyphsmith pack directory and zip with zero findings`(@TempDir dir: Path) {
        val cli = GlyphsmithCli().subcommands(InitCommand(), BuildCommand(), AuditCommand(), PlanCommand())
        val spec = dir.resolve("glyphs.yml")
        cli.parse(listOf("init", spec.toString()))
        val packDir = dir.resolve("pack")
        val packZip = dir.resolve("pack.zip")
        cli.parse(
            listOf(
                "build",
                spec.toString(),
                "--out",
                packDir.toString(),
                "--zip",
                packZip.toString(),
                "--json",
            ),
        )

        val dirReport = PackAuditor.audit(packDir)
        assertEquals(1, dirReport.fontCount)
        assertEquals(0, dirReport.errorCount)
        assertEquals(0, dirReport.warningCount)

        val zipReport = PackAuditor.audit(packZip)
        assertEquals(1, zipReport.fontCount)
        assertEquals(0, zipReport.errorCount)
        assertEquals(0, zipReport.warningCount)
    }

    @Test
    fun `flags ascent above height, missing texture, grid mismatch, shadowed codepoints, and BMP PUA`(@TempDir dir: Path) {
        val pack = dir.resolve("broken-pack")
        // Write a 10x9 texture allocated to a 2x1 grid -> 10 divides by 2, 9 divides by 1;
        // make a 9x8 texture on a 2x1 grid so 9 % 2 != 0 triggers BITMAP_GRID_MISMATCH.
        Texture.blank(9, 8).write(pack.resolve("assets/demo/textures/font/grid_odd.png"))
        Texture.blank(300, 8).write(pack.resolve("assets/demo/textures/font/oversized.png"))

        val fontJson = """
            {
              "providers": [
                {
                  "type": "bitmap",
                  "file": "demo:font/missing.png",
                  "height": 8,
                  "ascent": 12,
                  "chars": ["\uE001"]
                },
                {
                  "type": "bitmap",
                  "file": "demo:font/grid_odd.png",
                  "height": 8,
                  "ascent": 7,
                  "chars": ["\uE001\uE002"]
                },
                {
                  "type": "bitmap",
                  "file": "demo:font/oversized.png",
                  "height": 8,
                  "ascent": 7,
                  "chars": ["\uE003"]
                }
              ]
            }
        """.trimIndent()
        PackWriter.writeText(pack, "assets/demo/font/default.json", fontJson)

        val report = PackAuditor.audit(pack)
        val rules = report.findings.map { it.rule }.toSet()
        assertTrue("BITMAP_ASCENT_EXCEEDS_HEIGHT" in rules, "Expected ascent error in $rules")
        assertTrue("BITMAP_MISSING_TEXTURE" in rules, "Expected missing texture error in $rules")
        assertTrue("BITMAP_GRID_MISMATCH" in rules, "Expected grid mismatch warning in $rules")
        assertTrue("BITMAP_CELL_EXCEEDS_ATLAS" in rules, "Expected atlas page limit error in $rules")
        assertTrue("CODEPOINT_SHADOWED" in rules, "Expected shadowed codepoint warning in $rules")
        assertTrue("CODEPOINT_BMP_PUA_HAZARD" in rules, "Expected BMP PUA warning in $rules")

        val parsedJson = Json.parseToJsonElement(report.toJsonString()).jsonObject
        assertEquals("3", parsedJson["providerCount"]?.jsonPrimitive?.content)
    }

    @Test
    fun `--locked passes when lock matches and fails on unpinned glyph or artwork drift`(@TempDir dir: Path) {
        val cli = GlyphsmithCli().subcommands(InitCommand(), BuildCommand())
        val spec = dir.resolve("glyphs.yml")
        cli.parse(listOf("init", spec.toString()))
        val outDir = dir.resolve("pack")

        // Initial build writes glyphs.lock.json
        cli.parse(listOf("build", spec.toString(), "--out", outDir.toString()))

        // --locked succeeds when nothing drifted
        cli.parse(listOf("build", spec.toString(), "--out", outDir.toString(), "--locked"))

        // Modify an existing PNG so its sha256 changes -> --locked must fail with exit code 2
        val coinPath = dir.resolve("art/hud.coin.png")
        Texture.blank(12, 12).write(coinPath)
        val ex = assertThrows<ProgramResult> {
            cli.parse(listOf("build", spec.toString(), "--out", outDir.toString(), "--locked"))
        }
        assertEquals(2, ex.statusCode)

        // Append an unpinned glyph -> --locked must also fail
        Files.writeString(
            spec,
            Files.readString(spec) + "\n  extra.icon:\n    image: art/hud.coin.png\n",
        )
        val ex2 = assertThrows<ProgramResult> {
            cli.parse(listOf("build", spec.toString(), "--out", outDir.toString(), "--locked"))
        }
        assertEquals(2, ex2.statusCode)
    }
}
