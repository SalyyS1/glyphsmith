package com.salyvn.glyphsmith.pack

import com.salyvn.glyphsmith.image.DemoArt
import com.salyvn.glyphsmith.image.Texture
import com.salyvn.glyphsmith.lock.LockFile
import com.salyvn.glyphsmith.spec.SpecReader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

/** The whole pipeline, from a spec on disk to the files a client would read. */
class PackBuilderTest {

    @TempDir
    lateinit var directory: Path

    private fun project(
        glyphs: String = DEFAULT_GLYPHS,
        versions: String = "1.20.1..26.2",
    ): Path {
        Files.createDirectories(directory.resolve("art"))
        DemoArt.panel().write(directory.resolve("art/ui.panel.png"))
        DemoArt.coin().write(directory.resolve("art/hud.coin.png"))
        val file = directory.resolve("glyphs.yml")
        Files.writeString(
            file,
            "namespace: myserver\nfont: myserver:ui\nversions: $versions\nglyphs:\n$glyphs\n",
        )
        return file
    }

    private fun build(spec: Path, previous: LockFile? = null): BuildResult =
        PackBuilder(SpecReader.read(spec), previous).build(directory.resolve("pack"))

    @Test
    fun `the pack holds a font, a mcmeta and one texture per glyph`() {
        val result = build(project())
        val root = result.root

        assertTrue(root.resolve("pack.mcmeta").exists())
        assertTrue(root.resolve("assets/myserver/font/ui.json").exists())
        assertTrue(root.resolve("assets/myserver/textures/font/ui.panel.png").exists())
        assertTrue(root.resolve("assets/myserver/textures/font/hud.coin.png").exists())

        val font = Json.parseToJsonElement(Files.readString(root.resolve("assets/myserver/font/ui.json")))
        val providers = font.jsonObject.getValue("providers").jsonArray
        assertEquals("space", providers[0].jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("bitmap", providers[1].jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(
            "myserver:font/ui.panel.png",
            providers[1].jsonObject.getValue("file").jsonPrimitive.content,
        )
        assertEquals(3, providers.size, "one space provider and two bitmaps")
    }

    @Test
    fun `the placed panel is lifted to the window corner and the icon is left alone`() {
        val result = build(project())

        val panel = result.glyphs.first { it.spec.id == "ui.panel" }
        assertEquals(13, panel.solved.ascent, "6px above the title's origin is ascent 13")
        assertEquals(64, panel.solved.height)
        assertEquals(-8, panel.solved.leadShift)

        val coin = result.glyphs.first { it.spec.id == "hud.coin" }
        assertEquals(8, coin.solved.ascent)
        assertEquals(9, coin.solved.height)
        assertEquals(0, coin.solved.leadShift)
    }

    @Test
    fun `a placed glyph leaves the cursor where it found it`() {
        val result = build(project())
        val panel = result.glyphs.first { it.spec.id == "ui.panel" }

        assertEquals(0, travelled(result, panel.snippet.raw), "the panel must not push the title sideways")
    }

    @Test
    fun `an inline glyph advances the line instead of restoring it`() {
        val result = build(project())
        val coin = result.glyphs.first { it.spec.id == "hud.coin" }

        assertEquals(coin.solved.totalAdvance, travelled(result, coin.snippet.raw))
    }

    @Test
    fun `a rebuild keeps every codepoint even when the spec grows`() {
        val spec = project()
        val first = build(spec)
        val panelBefore = first.glyphs.first { it.spec.id == "ui.panel" }.codepoints

        val grown = project(glyphs = WITH_BADGE)
        val second = build(grown, first.lock)

        assertEquals(panelBefore, second.glyphs.first { it.spec.id == "ui.panel" }.codepoints)
        assertEquals(listOf("ui.badge"), second.newCodepoints, "only the new glyph takes a new codepoint")
    }

    @Test
    fun `a glyph dropped from the spec keeps its codepoint reserved`() {
        val first = build(project())
        val smaller = project(glyphs = ONLY_COIN)
        val second = build(smaller, first.lock)

        assertTrue(second.warnings.any { warning -> warning.contains("ui.panel") }, second.warnings.toString())
        assertTrue(second.lock.codepoints.containsKey("ui.panel"))
    }

    @Test
    fun `a source wider than an atlas page is stitched back together seamlessly`() {
        Files.createDirectories(directory.resolve("art"))
        val wide = Texture.blank(600, 32)
        for (x in 0 until 600) {
            for (y in 0 until 32) wide.image.setRGB(x, y, 0xFF3366AA.toInt())
        }
        wide.write(directory.resolve("art/ui.banner.png"))
        DemoArt.panel().write(directory.resolve("art/ui.panel.png"))
        DemoArt.coin().write(directory.resolve("art/hud.coin.png"))
        val spec = directory.resolve("glyphs.yml")
        Files.writeString(
            spec,
            """
            namespace: myserver
            glyphs:
              ui.banner:
                image: art/ui.banner.png
                at: [0, 0]
            """.trimIndent(),
        )

        val result = build(spec)
        val banner = result.glyphs.single()

        assertEquals(3, banner.solved.columns)
        assertEquals(3, banner.codepoints.size)
        // Each cell's pixels end one pixel before its advance does, so the seam needs a single pixel back.
        assertEquals(listOf(-1, -1, -1), banner.solved.seamCorrections())
        assertEquals(0, travelled(result, banner.snippet.raw))
    }

    @Test
    fun `the mcmeta matches the range the spec asked for`() {
        val legacy = build(project(versions = "1.20.1..26.2"))
        val text = Files.readString(legacy.root.resolve("pack.mcmeta"))

        assertTrue(text.contains("\"pack_format\": 15"))
        assertTrue(text.contains("\"min_format\": 15"))
        assertTrue(text.contains("\"max_format\": 88"))
        assertTrue(text.contains("supported_formats"))
    }

    @Test
    fun `the lock file survives a round trip`() {
        val result = build(project())
        val target = directory.resolve("glyphs.lock.json")
        result.lock.write(target)

        val read = LockFile.read(target)!!
        assertEquals(result.lock.font, read.font)
        assertEquals(result.lock.codepoints, read.codepoints)
        assertEquals(result.lock.glyphs, read.glyphs)
    }

    /** How far a snippet moves the cursor: shifts by their advance, cells by theirs. */
    private fun travelled(result: BuildResult, run: String): Int {
        val shiftPixels = result.shifts?.provider()?.advances ?: emptyMap()
        val cellAdvance = HashMap<Int, Int>()
        result.glyphs.forEach { glyph ->
            glyph.codepoints.forEachIndexed { index, codepoint -> cellAdvance[codepoint] = glyph.solved.advances[index] }
        }
        var total = 0
        var index = 0
        while (index < run.length) {
            val codepoint = run.codePointAt(index)
            total += shiftPixels[codepoint] ?: cellAdvance.getValue(codepoint)
            index += Character.charCount(codepoint)
        }
        return total
    }

    private companion object {
        val DEFAULT_GLYPHS = listOf(
            "  ui.panel:",
            "    image: art/ui.panel.png",
            "    anchor: container-title",
            "    at: [0, 0]",
            "  hud.coin:",
            "    image: art/hud.coin.png",
            "    height: 9",
            "    ascent: 8",
        ).joinToString("\n")

        val WITH_BADGE = listOf(
            "  ui.badge:",
            "    image: art/hud.coin.png",
            "    height: 9",
            "    ascent: 8",
        ).joinToString("\n") + "\n" + DEFAULT_GLYPHS

        val ONLY_COIN = listOf(
            "  hud.coin:",
            "    image: art/hud.coin.png",
            "    height: 9",
            "    ascent: 8",
        ).joinToString("\n")
    }
}
