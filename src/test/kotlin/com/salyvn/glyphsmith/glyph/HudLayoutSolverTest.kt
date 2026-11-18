package com.salyvn.glyphsmith.glyph

import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import com.salyvn.glyphsmith.GlyphsmithCli
import com.salyvn.glyphsmith.audit.PackAuditor
import com.salyvn.glyphsmith.cli.LayoutCommand
import com.salyvn.glyphsmith.image.DemoArt
import com.salyvn.glyphsmith.pack.PackWriter
import com.salyvn.glyphsmith.report.SarifReport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

class HudLayoutSolverTest {

    @Test
    fun `actionbar and bossbar composite layouts balance net cursor advance to exact zero`() {
        val actionbarSpec = HudLayoutSpec(
            font = "myserver:hud",
            context = HudContext.ACTIONBAR,
            layers = listOf(
                HudLayerInput(id = "bar_bg", x = -91, y = -5, widthPx = 182, heightPx = 10, advancePx = 183, zIndex = 0),
                HudLayerInput(id = "hp_fill", x = -89, y = -3, widthPx = 84, heightPx = 6, advancePx = 85, zIndex = 1),
                HudLayerInput(id = "mana_icon", x = -104, y = -5, widthPx = 9, heightPx = 9, advancePx = 10, zIndex = 1),
            ),
        )
        val solved = HudLayoutSolver.solve(actionbarSpec)
        assertEquals(0, solved.finalNetAdvancePx, "Centered actionbar layout must have 0px net cursor advance")
        assertEquals(12, solved.layers.first { it.id == "bar_bg" }.solvedAscent)
        assertEquals(12, solved.layers.first { it.id == "bar_bg" }.solvedHeight)
        assertEquals(2, solved.layers.first { it.id == "bar_bg" }.padBottomPx)

        // Verify summed shift steps + glyph advances equal exact 0px
        val cursorCheck = solved.layers.sumOf { it.preShiftPx + it.vanillaAdvancePx } + solved.tailShiftPx
        assertEquals(0, cursorCheck)

        // Bossbar originY = -9: aiming a frame at y=0 (bar top edge) converts to textOriginY=+9 -> ascent = 7 - 9 = -2
        val bossbarSpec = HudLayoutSpec(
            font = "myserver:boss",
            context = HudContext.BOSSBAR,
            layers = listOf(
                HudLayerInput(id = "boss_frame", x = -91, y = 0, widthPx = 182, heightPx = 7, advancePx = 183, zIndex = 0),
            ),
        )
        val bossSolved = HudLayoutSolver.solve(bossbarSpec)
        val frame = bossSolved.layers.single()
        assertEquals(9, frame.textOriginY)
        assertEquals(-2, frame.solvedAscent)
        assertEquals(7, frame.solvedHeight)
        assertEquals(0, bossSolved.finalNetAdvancePx)
    }

    @Test
    fun `Held-Karp scheduler minimizes NAF shift glyphs across disjoint layers while preserving overlap painter order`() {
        val disjointZigZag = listOf(
            HudLayerInput(id = "right_badge", x = 80, y = 0, widthPx = 9, heightPx = 9, advancePx = 10, zIndex = 0),
            HudLayerInput(id = "left_badge", x = -80, y = 0, widthPx = 9, heightPx = 9, advancePx = 10, zIndex = 0),
            HudLayerInput(id = "center_badge", x = 0, y = 0, widthPx = 9, heightPx = 9, advancePx = 10, zIndex = 0),
        )
        val preserved = HudLayoutSolver.solve(
            HudLayoutSpec(layers = disjointZigZag, optimizeOrder = false),
        )
        val optimized = HudLayoutSolver.solve(
            HudLayoutSpec(layers = disjointZigZag, optimizeOrder = true),
        )
        assertTrue(optimized.reorderedLayers)
        assertTrue(
            optimized.totalShiftGlyphsNaf < preserved.totalShiftGlyphsNaf,
            "Expected Held-Karp NAF count (${optimized.totalShiftGlyphsNaf}) < preserved (${preserved.totalShiftGlyphsNaf})",
        )
        assertEquals(0, optimized.finalNetAdvancePx)

        // Overlapping layers at the same zIndex must NEVER swap order, even if swapping would save shifts.
        val overlapping = listOf(
            HudLayerInput(id = "panel_under", x = 40, y = 0, widthPx = 60, heightPx = 20, advancePx = 61, zIndex = 0),
            HudLayerInput(id = "badge_over", x = 44, y = 2, widthPx = 12, heightPx = 12, advancePx = 13, zIndex = 0),
            HudLayerInput(id = "fg_top", x = -20, y = 0, widthPx = 10, heightPx = 10, advancePx = 11, zIndex = 1),
        )
        val overlapSolved = HudLayoutSolver.solve(HudLayoutSpec(layers = overlapping, optimizeOrder = true))
        val ids = overlapSolved.layers.map { it.id }
        assertTrue(ids.indexOf("panel_under") < ids.indexOf("badge_over"), "Overlapping same-z layers must keep painter order")
        assertEquals("fg_top", ids.last(), "Higher zIndex layer must execute after lower zIndex layers")
    }

    @Test
    fun `parses YAML HUD layout with real PNG image metrics and executes LayoutCommand CLI`(@TempDir dir: Path) {
        Files.createDirectories(dir.resolve("art"))
        DemoArt.coin().write(dir.resolve("art/coin.png"))
        val hudYml = dir.resolve("hud.yml")
        Files.writeString(
            hudYml,
            """
            namespace: myserver
            font: myserver:hud
            context: actionbar
            shifts: 1024
            compact_shifts: true
            layers:
              hud.bg:
                at: [-91, -5]
                size: [182, 10]
                z: 0
              hud.coin:
                image: art/coin.png
                x: -104
                y: -5
                z: 1
            """.trimIndent(),
        )

        val parsed = HudLayoutSolver.parseYamlSpec(hudYml)
        val solved = HudLayoutSolver.solve(parsed)
        assertEquals(2, solved.layers.size)
        assertEquals(0, solved.finalNetAdvancePx)
        assertTrue(solved.snippets.getValue("minimessage").startsWith("<font:myserver:hud>"))

        val cli = GlyphsmithCli().subcommands(LayoutCommand())
        cli.parse(
            listOf(
                "layout",
                "--context",
                "actionbar",
                "--layer",
                "bg:182x10@-91,-5:z=0",
                "--layer",
                "hp:84x6@-89,-3:z=1",
                "--json",
            ),
        )
    }

    @Test
    fun `PackAuditor detects opaque RGB bitmap bleed, transparent sprite-sheet cell collapse, and HUD shift table gaps`(@TempDir dir: Path) {
        val pack = dir.resolve("hud-audit-pack")
        Files.createDirectories(pack.resolve("assets/demo/textures/font"))

        // 1. RGB image without alpha channel -> BITMAP_OPAQUE_BACKGROUND_BLEED
        val opaqueRgb = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
        ImageIO.write(opaqueRgb, "png", pack.resolve("assets/demo/textures/font/no_alpha.png").toFile())

        // 2. 2-column sprite sheet where cell 0 has ink and cell 1 is 100% transparent -> BITMAP_TRANSPARENT_CELL_COLLAPSE
        val sheet = BufferedImage(16, 8, BufferedImage.TYPE_INT_ARGB)
        sheet.setRGB(2, 2, 0xFFFFFFFF.toInt())
        ImageIO.write(sheet, "png", pack.resolve("assets/demo/textures/font/sheet.png").toFile())

        PackWriter.writeText(
            pack,
            "assets/demo/font/hud.json",
            """
            {
              "providers": [
                {
                  "type": "space",
                  "advances": {
                    "\uDB80\uDC01": -1,
                    "\uDB80\uDC02": -2,
                    "\uDB80\uDC03": -8,
                    "\uDB80\uDC04": -16
                  }
                },
                {
                  "type": "bitmap",
                  "file": "demo:font/no_alpha.png",
                  "height": 8,
                  "ascent": 7,
                  "chars": ["\uDB80\uDC10"]
                },
                {
                  "type": "bitmap",
                  "file": "demo:font/sheet.png",
                  "height": 8,
                  "ascent": 7,
                  "chars": ["\uDB80\uDC11\uDB80\uDC12"]
                }
              ]
            }
            """.trimIndent(),
        )

        val report = PackAuditor.audit(pack)
        val rules = report.findings.map { it.rule }.toSet()
        assertTrue("BITMAP_OPAQUE_BACKGROUND_BLEED" in rules, "Expected opaque RGB warning in $rules")
        assertTrue("BITMAP_TRANSPARENT_CELL_COLLAPSE" in rules, "Expected transparent cell collapse warning in $rules")
        assertTrue("HUD_SHIFT_TABLE_GAP" in rules, "Expected shift table gap (-4px missing) warning in $rules")

        val sarif = SarifReport.render(report)
        val parsedSarif = Json.parseToJsonElement(sarif).jsonObject
        assertEquals("2.1.0", parsedSarif["version"]!!.toString().trim('"'))
        assertTrue(sarif.contains("HUD_SHIFT_TABLE_GAP"))
        assertTrue(sarif.contains("BITMAP_TRANSPARENT_CELL_COLLAPSE"))
        assertTrue(sarif.contains("BITMAP_OPAQUE_BACKGROUND_BLEED"))
    }
}
