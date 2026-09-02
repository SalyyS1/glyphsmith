package com.salyvn.glyphsmith.spec

import com.salyvn.glyphsmith.glyph.Anchor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SpecReaderTest {

    @TempDir
    lateinit var directory: Path

    private fun spec(yaml: String): Path {
        val file = directory.resolve("glyphs.yml")
        Files.writeString(file, yaml.trimIndent())
        return file
    }

    @Test
    fun `a minimal spec fills in the defaults`() {
        val parsed = SpecReader.read(
            spec(
                """
                namespace: myserver
                glyphs:
                  ui.panel:
                    image: art/panel.png
                """,
            ),
        )

        assertEquals("myserver:ui", parsed.font)
        assertEquals(1024, parsed.shiftRange)
        assertEquals(0xF0000, parsed.startCodepoint)
        assertEquals(1, parsed.glyphs.size)
        assertEquals(Anchor.TEXT, parsed.glyphs.single().anchor)
        assertNull(parsed.glyphs.single().placement)
        assertEquals(directory.resolve("art/panel.png").normalize(), parsed.glyphs.single().image)
    }

    @Test
    fun `an anchored placement is converted into offsets from the text origin`() {
        val parsed = SpecReader.read(
            spec(
                """
                namespace: myserver
                glyphs:
                  ui.panel:
                    image: art/panel.png
                    anchor: container-title
                    at: [0, 0]
                """,
            ),
        )

        // The container title is drawn at 8,6 inside the window, so the window's corner is -8,-6 from it.
        assertEquals(-8, parsed.glyphs.single().placement!!.x)
        assertEquals(-6, parsed.glyphs.single().placement!!.y)
        assertTrue(parsed.glyphs.single().restoreCursor, "a placed glyph puts the cursor back by default")
    }

    @Test
    fun `a mistyped key is named rather than ignored`() {
        val failure = assertThrows<SpecException> {
            SpecReader.read(
                spec(
                    """
                    namespace: myserver
                    shfits: 512
                    glyphs:
                      ui.panel:
                        image: art/panel.png
                    """,
                ),
            )
        }

        assertTrue(failure.message!!.contains("shfits"), failure.message)
    }

    @Test
    fun `a mistyped glyph key names the glyph too`() {
        val failure = assertThrows<SpecException> {
            SpecReader.read(
                spec(
                    """
                    namespace: myserver
                    glyphs:
                      ui.panel:
                        image: art/panel.png
                        ascnet: 8
                    """,
                ),
            )
        }

        assertTrue(failure.message!!.contains("ui.panel"))
        assertTrue(failure.message!!.contains("ascnet"))
    }

    @Test
    fun `an ascent without a height is refused because the pair is what the client checks`() {
        val failure = assertThrows<SpecException> {
            SpecReader.read(
                spec(
                    """
                    namespace: myserver
                    glyphs:
                      hud.coin:
                        image: art/coin.png
                        ascent: 8
                    """,
                ),
            )
        }

        assertTrue(failure.message!!.contains("height"), failure.message)
    }

    @Test
    fun `asking for a position and an ascent at the same time is contradictory`() {
        val failure = assertThrows<SpecException> {
            SpecReader.read(
                spec(
                    """
                    namespace: myserver
                    glyphs:
                      hud.coin:
                        image: art/coin.png
                        at: [0, 0]
                        ascent: 8
                        height: 9
                    """,
                ),
            )
        }

        assertTrue(failure.message!!.contains("at"))
    }

    @Test
    fun `a version range becomes two pack formats`() {
        val parsed = SpecReader.read(
            spec(
                """
                namespace: myserver
                versions: 1.21.4..26.2
                glyphs:
                  ui.panel:
                    image: art/panel.png
                """,
            ),
        )

        assertEquals(46, parsed.minFormat.major)
        assertEquals(88, parsed.maxFormat.major)
    }

    @Test
    fun `an unknown version says which ones are known`() {
        val failure = assertThrows<SpecException> {
            SpecReader.read(
                spec(
                    """
                    namespace: myserver
                    versions: 1.19.2..26.2
                    glyphs:
                      ui.panel:
                        image: art/panel.png
                    """,
                ),
            )
        }

        assertTrue(failure.message!!.contains("1.21.4"), "the list of known releases should be in the message")
    }

    @Test
    fun `a spec with no glyphs is not a spec`() {
        assertThrows<SpecException> { SpecReader.read(spec("namespace: myserver")) }
    }
}
