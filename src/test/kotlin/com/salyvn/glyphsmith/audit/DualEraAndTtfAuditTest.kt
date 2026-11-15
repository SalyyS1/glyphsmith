package com.salyvn.glyphsmith.audit

import com.salyvn.glyphsmith.image.DemoArt
import com.salyvn.glyphsmith.image.Texture
import com.salyvn.glyphsmith.pack.PackBuilder
import com.salyvn.glyphsmith.pack.PackWriter
import com.salyvn.glyphsmith.report.SarifReport
import com.salyvn.glyphsmith.spec.SpecReader
import com.salyvn.glyphsmith.ttf.SfntFixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

class DualEraAndTtfAuditTest {

    @Test
    fun `builds dual-era 1_20_1_to_26_3 pack with format 97 overlay, filter, compact NAF shifts, and TTF provider`(@TempDir dir: Path) {
        Files.createDirectories(dir.resolve("art"))
        Files.createDirectories(dir.resolve("fonts"))
        DemoArt.panel().write(dir.resolve("art/ui.panel.png"))
        DemoArt.coin().write(dir.resolve("art/hud.coin.png"))
        Files.write(dir.resolve("fonts/body.ttf"), SfntFixtures.buildStaticFont())

        val specPath = dir.resolve("glyphs.yml")
        Files.writeString(
            specPath,
            """
            namespace: myserver
            font: myserver:ui
            versions: 1.20.1..26.3
            compact_shifts: true
            glyphs:
              ui.panel:
                image: art/ui.panel.png
                anchor: container-title
                at: [0, 0]
              hud.badge26:
                image: art/hud.coin.png
                height: 9
                ascent: 8
                versions: 26.3
                filter:
                  uniform: false
            ttf:
              body.sans:
                file: fonts/body.ttf
                size: 11.0
                oversample: 2.0
                shift: [0.0, 0.5]
                versions: 26.3
                filter:
                  jp: false
            """.trimIndent(),
        )

        val packDir = dir.resolve("pack")
        val spec = SpecReader.read(specPath)
        val result = PackBuilder(spec, previous = null).build(packDir)

        assertEquals(1, result.overlays.size)
        assertEquals("overlay_fmt_97", result.overlays.single().directory)
        assertTrue(packDir.resolve("overlay_fmt_97/assets/myserver/font/ui.json").exists())
        assertTrue(packDir.resolve("overlay_fmt_97/assets/myserver/font/body.sans.ttf").exists())

        val mcmeta = Json.parseToJsonElement(Files.readString(packDir.resolve("pack.mcmeta"))).jsonObject
        val packObj = mcmeta["pack"]!!.jsonObject
        assertEquals(15, packObj["pack_format"]!!.jsonPrimitive.int)
        assertEquals(97, packObj["max_format"]!!.jsonPrimitive.int)
        val overlayEntry = mcmeta["overlays"]!!.jsonObject["entries"]!!.jsonArray.single().jsonObject
        assertEquals("overlay_fmt_97", overlayEntry["directory"]!!.jsonPrimitive.content)
        assertEquals(97, overlayEntry["min_format"]!!.jsonPrimitive.int)
        assertEquals(97, overlayEntry["max_format"]!!.jsonPrimitive.int)
        assertFalse(overlayEntry.containsKey("formats"), "Modern format 97 overlay must not emit legacy 'formats'")

        val overlayFont = Json.parseToJsonElement(
            Files.readString(packDir.resolve("overlay_fmt_97/assets/myserver/font/ui.json")),
        ).jsonObject
        val ovProviders = overlayFont["providers"]!!.jsonArray
        assertEquals(4, ovProviders.size, "space + base panel + 26.3 badge + 26.3 ttf")
        assertEquals(
            false,
            ovProviders[2].jsonObject["filter"]!!.jsonObject["uniform"]!!.jsonPrimitive.boolean,
        )
        assertEquals("ttf", ovProviders[3].jsonObject["type"]!!.jsonPrimitive.content)

        // PackAuditor must validate the entire dual-era + format-97 overlay pack with zero errors and warnings
        val audit = PackAuditor.audit(packDir)
        assertEquals(0, audit.errorCount, "Unexpected errors: ${audit.findings}")
        assertEquals(0, audit.warningCount, "Unexpected warnings: ${audit.findings}")
    }

    @Test
    fun `audits TTF variable axes, WOFF2, misplaced textures, filters, unihex, and format 97 mcmeta conflicts`(@TempDir dir: Path) {
        val pack = dir.resolve("broken-v040-pack")
        Texture.blank(8, 8).write(pack.resolve("assets/demo/textures/font/ok.png"))
        // Variable font in assets/demo/font/var.ttf
        Files.createDirectories(pack.resolve("assets/demo/font"))
        Files.write(pack.resolve("assets/demo/font/var.ttf"), SfntFixtures.buildVariableFont())
        // WOFF2 binary in assets/demo/font/web.woff2
        Files.write(pack.resolve("assets/demo/font/web.woff2"), "wOF2".toByteArray(StandardCharsets.US_ASCII) + ByteArray(24))
        // Misplaced TTF inside textures/ instead of font/
        Files.createDirectories(pack.resolve("assets/demo/textures"))
        Files.write(pack.resolve("assets/demo/textures/misplaced.ttf"), SfntFixtures.buildStaticFont())

        // Modern pack (min_format 88, max_format 97) illegally declaring supported_formats, plus modern overlay with legacy formats
        PackWriter.writeText(
            pack,
            "pack.mcmeta",
            """
            {
              "pack": {
                "description": "broken 26.3 pack",
                "supported_formats": [88, 97],
                "min_format": 88,
                "max_format": 97
              },
              "overlays": {
                "entries": [
                  {
                    "directory": "ov97",
                    "formats": [97, 97],
                    "min_format": 97,
                    "max_format": 97
                  }
                ]
              }
            }
            """.trimIndent(),
        )
        PackWriter.writeText(
            pack,
            "ov97/assets/demo/font/extra.json",
            """{ "providers": [ { "type": "bitmap", "file": "demo:font/ok.png", "height": 8, "ascent": 7, "chars": ["\uDB80\uDC01"] } ] }""",
        )

        PackWriter.writeText(
            pack,
            "assets/demo/font/main.json",
            """
            {
              "providers": [
                {
                  "type": "ttf",
                  "file": "demo:var.ttf",
                  "size": 11.0,
                  "oversample": 2.0,
                  "skip": "AVW"
                },
                {
                  "type": "ttf",
                  "file": "demo:web.woff2",
                  "size": 0,
                  "shift": [1.0]
                },
                {
                  "type": "ttf",
                  "file": "demo:misplaced.ttf"
                },
                {
                  "type": "unihex",
                  "hex_file": "demo:font/missing_unifont.zip",
                  "size_overrides": [
                    { "from": "AB", "to": "C", "left": 0, "right": 7 }
                  ]
                },
                {
                  "type": "bitmap",
                  "file": "demo:font/ok.png",
                  "height": 8,
                  "ascent": 7,
                  "chars": ["\uDB80\uDC02"],
                  "filter": { "bold": true, "uniform": "yes" }
                }
              ]
            }
            """.trimIndent(),
        )

        val report = PackAuditor.audit(pack)
        val rules = report.findings.map { it.rule }.toSet()

        assertTrue("PACK_MCMETA_FORMAT_CONFLICT" in rules, "Expected pack.mcmeta conflict in $rules")
        assertTrue("OVERLAY_FORMAT_CONFLICT" in rules, "Expected overlay conflict in $rules")
        assertTrue("TTF_VARIABLE_FONT_UNPINNED_AXES" in rules, "Expected fvar warning in $rules")
        assertTrue("TTF_EMPTY_CMAP" in rules, "Expected empty cmap warning after skip='AVW' in $rules")
        assertTrue("TTF_UNSUPPORTED_CONTAINER" in rules, "Expected WOFF2 container error in $rules")
        assertTrue("TTF_INVALID_METRICS" in rules, "Expected size/shift error in $rules")
        assertTrue("TTF_MISSING_FILE" in rules, "Expected misplaced ttf missing error in $rules")
        assertTrue("UNIHEX_INVALID_PROVIDER" in rules, "Expected unihex error in $rules")
        assertTrue("PROVIDER_INVALID_FILTER" in rules, "Expected filter error in $rules")

        val misplacedMsg = report.findings.first { it.rule == "TTF_MISSING_FILE" }.message
        assertTrue(misplacedMsg.contains("assets/demo/textures/misplaced.ttf"), misplacedMsg)

        // Ensure SarifReport maps every new rule descriptor cleanly
        val sarif = Json.parseToJsonElement(SarifReport.render(report)).jsonObject
        val sarifRules = sarif["runs"]!!.jsonArray.single().jsonObject["tool"]!!.jsonObject["driver"]!!
            .jsonObject["rules"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }.toSet()
        assertTrue(sarifRules.containsAll(rules))
    }
}
