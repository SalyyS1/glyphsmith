package com.salyvn.glyphsmith.audit

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import com.salyvn.glyphsmith.GlyphsmithCli
import com.salyvn.glyphsmith.cli.AuditCommand
import com.salyvn.glyphsmith.cli.BuildCommand
import com.salyvn.glyphsmith.cli.InitCommand
import com.salyvn.glyphsmith.cli.PlanCommand
import com.salyvn.glyphsmith.core.PackFormats
import com.salyvn.glyphsmith.image.Texture
import com.salyvn.glyphsmith.pack.PackWriter
import com.salyvn.glyphsmith.report.SarifReport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

        cli.parse(listOf("build", spec.toString(), "--out", outDir.toString()))
        cli.parse(listOf("build", spec.toString(), "--out", outDir.toString(), "--locked"))

        val coinPath = dir.resolve("art/hud.coin.png")
        Texture.blank(12, 12).write(coinPath)
        val ex = assertThrows<ProgramResult> {
            cli.parse(listOf("build", spec.toString(), "--out", outDir.toString(), "--locked"))
        }
        assertEquals(2, ex.statusCode)

        Files.writeString(
            spec,
            Files.readString(spec) + "\n  extra.icon:\n    image: art/hud.coin.png\n",
        )
        val ex2 = assertThrows<ProgramResult> {
            cli.parse(listOf("build", spec.toString(), "--out", outDir.toString(), "--locked"))
        }
        assertEquals(2, ex2.statusCode)
    }

    @Test
    fun `detects circular reference chains, missing reference targets, and invalid space or bitmap bounds`(@TempDir dir: Path) {
        val pack = dir.resolve("ref-cycle-pack")
        Texture.blank(8, 8).write(pack.resolve("assets/demo/textures/font/dot.png"))

        PackWriter.writeText(
            pack,
            "assets/demo/font/a.json",
            """
            {
              "providers": [
                { "type": "reference", "id": "demo:b" },
                { "type": "reference", "id": "minecraft:include/space" },
                { "type": "reference", "id": "" },
                { "type": "space", "advances": { "\uDB80\uDC01": "-8", "\uDB80\uDC02": 12000 } },
                { "type": "bitmap", "file": "demo:font/dot.png", "height": 0, "ascent": 0, "chars": ["\uDB80\uDC03"] }
              ]
            }
            """.trimIndent(),
        )
        PackWriter.writeText(
            pack,
            "assets/demo/font/b.json",
            """
            {
              "providers": [
                { "type": "reference", "id": "demo:a" },
                { "type": "reference", "id": "demo:ghost" }
              ]
            }
            """.trimIndent(),
        )

        val report = PackAuditor.audit(pack)
        val rules = report.findings.map { it.rule }.toSet()
        assertTrue("REFERENCE_CYCLE" in rules, "Expected cycle in $rules")
        assertTrue("REFERENCE_MISSING_ID" in rules, "Expected missing id in $rules")
        assertTrue("REFERENCE_MISSING_TARGET" in rules, "Expected missing target demo:ghost in $rules")
        assertTrue("SPACE_INVALID_ADVANCE" in rules, "Expected invalid space advance in $rules")
        assertTrue("BITMAP_INVALID_HEIGHT" in rules, "Expected invalid bitmap height in $rules")
        assertFalse(
            report.findings.any { it.rule == "REFERENCE_MISSING_TARGET" && it.message.contains("minecraft:include/space") },
            "Built-in minecraft:* fonts must not trigger REFERENCE_MISSING_TARGET",
        )
    }

    @Test
    fun `audits pack mcmeta overlay directories with base texture fallback and flags invalid overlay directories`(@TempDir dir: Path) {
        val pack = dir.resolve("overlay-pack")
        // Shared texture only in base assets/
        Texture.blank(8, 8).write(pack.resolve("assets/demo/textures/font/shared.png"))

        PackWriter.writeText(
            pack,
            "pack.mcmeta",
            """
            {
              "pack": { "min_format": 88, "max_format": 97 },
              "overlays": {
                "entries": [
                  { "directory": "overlay_26_3", "min_format": 97, "max_format": 97 },
                  { "directory": "Bad/Dir", "min_format": 97, "max_format": 97 },
                  { "directory": "empty_overlay", "min_format": 97, "max_format": 97 }
                ]
              }
            }
            """.trimIndent(),
        )
        // Overlay font reuses base shared.png (valid) + has one ascent > height bug
        PackWriter.writeText(
            pack,
            "overlay_26_3/assets/demo/font/hud.json",
            """
            {
              "providers": [
                {
                  "type": "bitmap",
                  "file": "demo:font/shared.png",
                  "height": 8,
                  "ascent": 7,
                  "chars": ["\uDB80\uDC10"]
                },
                {
                  "type": "bitmap",
                  "file": "demo:font/shared.png",
                  "height": 8,
                  "ascent": 11,
                  "chars": ["\uDB80\uDC11"]
                }
              ]
            }
            """.trimIndent(),
        )

        val report = PackAuditor.audit(pack)
        assertEquals(1, report.fontCount)
        val overlayDirErrors = report.findings.filter { it.rule == "OVERLAY_INVALID_DIRECTORY" }
        assertEquals(2, overlayDirErrors.size, "Both Bad/Dir and missing empty_overlay must be reported")
        assertFalse(
            report.findings.any { it.rule == "BITMAP_MISSING_TEXTURE" },
            "Overlay font should resolve shared.png from base assets fallback",
        )
        val ascentErr = report.findings.single { it.rule == "BITMAP_ASCENT_EXCEEDS_HEIGHT" }
        assertEquals("overlay_26_3/assets/demo/font/hud.json", ascentErr.filePath)
    }

    @Test
    fun `audits multi-pack stack for cross-pack codepoint collisions and cross-pack references`(@TempDir dir: Path) {
        val packA = dir.resolve("base-ui")
        val packB = dir.resolve("plugin-items")
        Texture.blank(8, 8).write(packA.resolve("assets/minecraft/textures/font/a.png"))
        Texture.blank(8, 8).write(packB.resolve("assets/minecraft/textures/font/b.png"))

        PackWriter.writeText(
            packA,
            "assets/minecraft/font/default.json",
            """
            {
              "providers": [
                {
                  "type": "bitmap",
                  "file": "minecraft:font/a.png",
                  "height": 8,
                  "ascent": 7,
                  "chars": ["\uDB80\uDC20\uDB80\uDC21"]
                }
              ]
            }
            """.trimIndent(),
        )
        PackWriter.writeText(
            packA,
            "assets/shared/font/shifts.json",
            """
            {
              "providers": [
                { "type": "space", "advances": { "\uDB80\uDC01": -8 } }
              ]
            }
            """.trimIndent(),
        )
        // packB collides on U+F0021 in minecraft:default, and cleanly references shared:shifts from packA
        PackWriter.writeText(
            packB,
            "assets/minecraft/font/default.json",
            """
            {
              "providers": [
                { "type": "reference", "id": "shared:shifts" },
                {
                  "type": "bitmap",
                  "file": "minecraft:font/b.png",
                  "height": 8,
                  "ascent": 7,
                  "chars": ["\uDB80\uDC21\uDB80\uDC22"]
                }
              ]
            }
            """.trimIndent(),
        )

        val report = PackAuditor.auditStack(listOf(packA, packB))
        assertEquals(2, report.packCount)
        assertEquals(0, report.errorCount)
        val collisions = report.findings.filter { it.rule == "CROSS_PACK_CODEPOINT_COLLISION" }
        assertEquals(1, collisions.size)
        assertTrue(collisions.single().message.contains("U+F0021"))
        assertFalse(
            report.findings.any { it.rule == "REFERENCE_MISSING_TARGET" },
            "Cross-pack reference to shared:shifts defined in packA should succeed",
        )
    }

    @Test
    fun `emits valid OASIS SARIF v2_1_0 via SarifReport and CLI --sarif option`(@TempDir dir: Path) {
        val pack = dir.resolve("sarif-pack")
        PackWriter.writeText(
            pack,
            "assets/demo/font/broken.json",
            """
            {
              "providers": [
                {
                  "type": "bitmap",
                  "file": "demo:font/missing.png",
                  "height": 8,
                  "ascent": 10,
                  "chars": ["\uDB80\uDC01"]
                }
              ]
            }
            """.trimIndent(),
        )

        val sarifPath = dir.resolve("out/glyphsmith.sarif")
        val cli = GlyphsmithCli().subcommands(AuditCommand())
        val ex = assertThrows<ProgramResult> {
            cli.parse(listOf("audit", pack.toString(), "--sarif", sarifPath.toString(), "--json"))
        }
        assertEquals(2, ex.statusCode)
        assertTrue(Files.isRegularFile(sarifPath))

        val sarifRoot = Json.parseToJsonElement(Files.readString(sarifPath)).jsonObject
        assertEquals("2.1.0", sarifRoot["version"]?.jsonPrimitive?.content)
        val run = sarifRoot["runs"]!!.jsonArray.single().jsonObject
        val driver = run["tool"]!!.jsonObject["driver"]!!.jsonObject
        assertEquals("glyphsmith", driver["name"]?.jsonPrimitive?.content)
        val results = run["results"]!!.jsonArray
        assertEquals(2, results.size)
        val firstUri = results.first().jsonObject["locations"]!!.jsonArray.first().jsonObject["physicalLocation"]!!
            .jsonObject["artifactLocation"]!!.jsonObject["uri"]!!.jsonPrimitive.content
        assertEquals("assets/demo/font/broken.json", firstUri)

        // Verify 26.3 format mapping
        assertEquals(97, PackFormats.formatOf("26.3"))
    }
}
