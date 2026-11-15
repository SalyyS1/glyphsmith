package com.salyvn.glyphsmith.ttf

import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import com.salyvn.glyphsmith.GlyphsmithCli
import com.salyvn.glyphsmith.cli.TtfCommand
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

object SfntFixtures {

    fun buildStaticFont(
        cff: Boolean = false,
        codepoints: List<Int> = listOf('A'.code, 'V'.code, 'T'.code, 0xF0010),
        kernPairs: List<Triple<Int, Int, Int>> = listOf(Triple(1, 2, -120), Triple(1, 3, -20)),
        includeGposClassKern: Boolean = false,
    ): ByteArray {
        val tables = LinkedHashMap<String, ByteArray>()
        val numGlyphs = codepoints.size + 1
        tables["head"] = buildHead(unitsPerEm = 1000)
        tables["maxp"] = buildMaxp(numGlyphs = numGlyphs)
        tables["hhea"] = buildHhea(ascent = 800, descent = -200, lineGap = 90, numHMetrics = numGlyphs)
        tables["hmtx"] = buildHmtx(List(numGlyphs) { idx -> if (idx == 0) 500 else 600 })
        tables["name"] = buildName(
            listOf(
                1 to "Glyphsmith Sans",
                2 to "Regular",
                4 to "Glyphsmith Sans Regular",
            ),
        )
        tables["cmap"] = buildCmapFormat12(codepoints)
        if (kernPairs.isNotEmpty()) {
            tables["kern"] = buildKernV0(kernPairs)
        }
        if (includeGposClassKern) {
            tables["GPOS"] = buildGposPairPosFormat2()
        }
        return assembleSfnt(if (cff) 0x4F54544F else 0x00010000, tables)
    }

    fun buildVariableFont(): ByteArray {
        val tables = LinkedHashMap<String, ByteArray>()
        tables["head"] = buildHead(unitsPerEm = 1000)
        tables["maxp"] = buildMaxp(numGlyphs = 4)
        tables["hhea"] = buildHhea(ascent = 850, descent = -150, lineGap = 50, numHMetrics = 4)
        tables["hmtx"] = buildHmtx(listOf(500, 600, 620, 580))
        tables["name"] = buildName(
            listOf(
                1 to "Inter Pixel VF",
                2 to "Regular",
                4 to "Inter Pixel Variable",
                256 to "Weight",
                257 to "Width",
                258 to "Light",
                259 to "Bold",
                260 to "InterPixelVF-Bold",
            ),
        )
        tables["cmap"] = buildCmapFormat4(listOf('A'.code to 1, 'V'.code to 2, 'W'.code to 3))
        tables["fvar"] = buildFvar()
        tables["avar"] = buildAvar()
        tables["STAT"] = buildStat(axisCount = 2)
        tables["gvar"] = ByteArray(512) { (it and 0xFF).toByte() }
        tables["GPOS"] = buildGposPairPosFormat1(leftGlyph = 1, rightGlyph = 2, xAdvance = -110)
        return assembleSfnt(0x00010000, tables)
    }

    fun wrapInTtc(faceBytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)
        dos.writeInt(0x74746366) // 'ttcf'
        dos.writeInt(0x00010000) // major=1, minor=0
        dos.writeInt(1) // 1 font face
        dos.writeInt(16) // face #0 offset = 16
        // Patch table offsets inside faceBytes by +16!
        val patched = faceBytes.copyOf()
        val numTables = ((patched[4].toInt() and 0xFF) shl 8) or (patched[5].toInt() and 0xFF)
        for (i in 0 until numTables) {
            val pos = 12 + i * 16 + 8
            val oldOff = ((patched[pos].toInt() and 0xFF) shl 24) or
                ((patched[pos + 1].toInt() and 0xFF) shl 16) or
                ((patched[pos + 2].toInt() and 0xFF) shl 8) or
                (patched[pos + 3].toInt() and 0xFF)
            val newOff = oldOff + 16
            patched[pos] = (newOff ushr 24).toByte()
            patched[pos + 1] = (newOff ushr 16).toByte()
            patched[pos + 2] = (newOff ushr 8).toByte()
            patched[pos + 3] = newOff.toByte()
        }
        dos.write(patched)
        return out.toByteArray()
    }

    private fun assembleSfnt(sfntVersion: Int, tables: Map<String, ByteArray>): ByteArray {
        val sorted = tables.entries.sortedBy { it.key }
        val numTables = sorted.size
        val dirSize = 12 + numTables * 16
        var cursor = dirSize

        val headerOut = ByteArrayOutputStream()
        val dos = DataOutputStream(headerOut)
        dos.writeInt(sfntVersion)
        dos.writeShort(numTables)
        dos.writeShort(0)
        dos.writeShort(0)
        dos.writeShort(0)

        val bodyOut = ByteArrayOutputStream()
        for ((tag, data) in sorted) {
            val tagBytes = tag.toByteArray(StandardCharsets.US_ASCII)
            require(tagBytes.size == 4)
            dos.write(tagBytes)
            dos.writeInt(0) // checksum
            dos.writeInt(cursor)
            dos.writeInt(data.size)
            bodyOut.write(data)
            val pad = (4 - (data.size % 4)) % 4
            repeat(pad) { bodyOut.write(0) }
            cursor += data.size + pad
        }
        return headerOut.toByteArray() + bodyOut.toByteArray()
    }

    private fun buildHead(unitsPerEm: Int): ByteArray {
        val b = ByteArray(54)
        putU16(b, 0, 1)
        putU16(b, 18, unitsPerEm)
        putI16(b, 36, -50)
        putI16(b, 38, -200)
        putI16(b, 40, 920)
        putI16(b, 42, 850)
        return b
    }

    private fun buildMaxp(numGlyphs: Int): ByteArray {
        val b = ByteArray(6)
        putU16(b, 0, 1)
        putU16(b, 4, numGlyphs)
        return b
    }

    private fun buildHhea(ascent: Int, descent: Int, lineGap: Int, numHMetrics: Int): ByteArray {
        val b = ByteArray(36)
        putU16(b, 0, 1)
        putI16(b, 4, ascent)
        putI16(b, 6, descent)
        putI16(b, 8, lineGap)
        putU16(b, 10, 1000)
        putU16(b, 34, numHMetrics)
        return b
    }

    private fun buildHmtx(advances: List<Int>): ByteArray {
        val b = ByteArray(advances.size * 4)
        advances.forEachIndexed { idx, adv ->
            putU16(b, idx * 4, adv)
            putI16(b, idx * 4 + 2, 20)
        }
        return b
    }

    private fun buildName(records: List<Pair<Int, String>>): ByteArray {
        val headerLen = 6 + records.size * 12
        val storage = ByteArrayOutputStream()
        val head = ByteArrayOutputStream()
        val dos = DataOutputStream(head)
        dos.writeShort(0)
        dos.writeShort(records.size)
        dos.writeShort(headerLen)
        for ((nameId, value) in records) {
            val encoded = value.toByteArray(StandardCharsets.UTF_16BE)
            dos.writeShort(3) // Windows
            dos.writeShort(1) // Unicode BMP
            dos.writeShort(0x0409) // en-US
            dos.writeShort(nameId)
            dos.writeShort(encoded.size)
            dos.writeShort(storage.size())
            storage.write(encoded)
        }
        return head.toByteArray() + storage.toByteArray()
    }

    private fun buildCmapFormat12(codepoints: List<Int>): ByteArray {
        val subOut = ByteArrayOutputStream()
        val sdos = DataOutputStream(subOut)
        sdos.writeShort(12)
        sdos.writeShort(0)
        sdos.writeInt(16 + codepoints.size * 12)
        sdos.writeInt(0)
        sdos.writeInt(codepoints.size)
        codepoints.forEachIndexed { idx, cp ->
            sdos.writeInt(cp)
            sdos.writeInt(cp)
            sdos.writeInt(idx + 1)
        }
        val subBytes = subOut.toByteArray()

        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)
        dos.writeShort(0)
        dos.writeShort(1)
        dos.writeShort(3)
        dos.writeShort(10)
        dos.writeInt(12)
        dos.write(subBytes)
        return out.toByteArray()
    }

    private fun buildCmapFormat4(mappings: List<Pair<Int, Int>>): ByteArray {
        val sorted = mappings.sortedBy { it.first }
        val segCount = sorted.size + 1
        val segCountX2 = segCount * 2
        val subLen = 16 + segCount * 8
        val sub = ByteArrayOutputStream()
        val sdos = DataOutputStream(sub)
        sdos.writeShort(4)
        sdos.writeShort(subLen)
        sdos.writeShort(0)
        sdos.writeShort(segCountX2)
        sdos.writeShort(2)
        sdos.writeShort(1)
        sdos.writeShort(0)
        // endCode
        sorted.forEach { (cp, _) -> sdos.writeShort(cp) }
        sdos.writeShort(0xFFFF)
        // reservedPad
        sdos.writeShort(0)
        // startCode
        sorted.forEach { (cp, _) -> sdos.writeShort(cp) }
        sdos.writeShort(0xFFFF)
        // idDelta
        sorted.forEach { (cp, gid) -> sdos.writeShort((gid - cp) and 0xFFFF) }
        sdos.writeShort(1)
        // idRangeOffset (all 0)
        repeat(segCount) { sdos.writeShort(0) }

        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)
        dos.writeShort(0)
        dos.writeShort(1)
        dos.writeShort(3)
        dos.writeShort(1)
        dos.writeInt(12)
        dos.write(sub.toByteArray())
        return out.toByteArray()
    }

    private fun buildFvar(): ByteArray {
        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)
        dos.writeShort(1) // major
        dos.writeShort(0) // minor
        dos.writeShort(16) // axesArrayOffset
        dos.writeShort(2) // reserved
        dos.writeShort(2) // axisCount = 2
        dos.writeShort(20) // axisSize = 20
        dos.writeShort(2) // instanceCount = 2
        dos.writeShort(14) // instanceSize = 4 + 2*4 + 2 (includes postScriptNameID)

        // Axis 0: wght [100..900, default=400], flags=0, nameID=256
        dos.writeBytes("wght")
        dos.writeInt(100 shl 16)
        dos.writeInt(400 shl 16)
        dos.writeInt(900 shl 16)
        dos.writeShort(0)
        dos.writeShort(256)

        // Axis 1: wdth [75..125, default=100], flags=1 (hidden), nameID=257
        dos.writeBytes("wdth")
        dos.writeInt(75 shl 16)
        dos.writeInt(100 shl 16)
        dos.writeInt(125 shl 16)
        dos.writeShort(0x0001)
        dos.writeShort(257)

        // Instance 0: Light (wght=300, wdth=100), psName=0xFFFF
        dos.writeShort(258)
        dos.writeShort(0)
        dos.writeInt(300 shl 16)
        dos.writeInt(100 shl 16)
        dos.writeShort(0xFFFF)

        // Instance 1: Bold (wght=700, wdth=100), psName=260
        dos.writeShort(259)
        dos.writeShort(0)
        dos.writeInt(700 shl 16)
        dos.writeInt(100 shl 16)
        dos.writeShort(260)

        return out.toByteArray()
    }

    private fun buildAvar(): ByteArray {
        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)
        dos.writeShort(1)
        dos.writeShort(0)
        dos.writeShort(0)
        dos.writeShort(2) // 2 axes
        // Axis 0: 3 segment maps (-1->-1, 0->0, 1->1)
        dos.writeShort(3)
        repeat(3 * 2) { dos.writeShort(0) }
        // Axis 1: 0 segment maps
        dos.writeShort(0)
        return out.toByteArray()
    }

    private fun buildStat(axisCount: Int): ByteArray {
        val b = ByteArray(20)
        putU16(b, 0, 1)
        putU16(b, 2, 2)
        putU16(b, 4, 8)
        putU16(b, 6, axisCount)
        return b
    }

    private fun buildKernV0(pairs: List<Triple<Int, Int, Int>>): ByteArray {
        val subLen = 14 + pairs.size * 6
        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)
        dos.writeShort(0) // version 0
        dos.writeShort(1) // 1 subtable
        dos.writeShort(0) // subtable version
        dos.writeShort(subLen)
        dos.writeShort(0x0001) // format 0, horizontal
        dos.writeShort(pairs.size)
        dos.writeShort(0)
        dos.writeShort(0)
        dos.writeShort(0)
        for ((l, r, v) in pairs) {
            dos.writeShort(l)
            dos.writeShort(r)
            dos.writeShort(v)
        }
        return out.toByteArray()
    }

    private fun buildGposPairPosFormat1(leftGlyph: Int, rightGlyph: Int, xAdvance: Int): ByteArray {
        // GPOS v1.0 header (10B) -> LookupList at +10 (4B) -> Lookup at +14 (8B) -> PairPosFmt1 at +22
        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)
        dos.writeShort(1)
        dos.writeShort(0)
        dos.writeShort(0) // scriptList
        dos.writeShort(0) // featureList
        dos.writeShort(10) // lookupListOffset = 10

        // LookupList (at 10): lookupCount=1, lookup[0] rel offset = 4 -> 14
        dos.writeShort(1)
        dos.writeShort(4)

        // Lookup (at 14): lookupType=2 (PairPos), lookupFlag=0, subTableCount=1, subtable[0] rel offset = 8 -> 22
        dos.writeShort(2)
        dos.writeShort(0)
        dos.writeShort(1)
        dos.writeShort(8)

        // PairPosFormat1 (at 22):
        // posFormat=1, coverageOff=12 (->34), vf1=0x0004 (XAdvance), vf2=0, pairSetCount=1, pairSetOff[0]=18 (->40)
        dos.writeShort(1)
        dos.writeShort(12)
        dos.writeShort(0x0004)
        dos.writeShort(0)
        dos.writeShort(1)
        dos.writeShort(18)

        // Coverage Format 1 (at 34, 6 bytes): format=1, glyphCount=1, glyph=leftGlyph
        dos.writeShort(1)
        dos.writeShort(1)
        dos.writeShort(leftGlyph)

        // PairSet (at 40): pairValueCount=1, secondGlyph=rightGlyph, value1.xAdvance=xAdvance
        dos.writeShort(1)
        dos.writeShort(rightGlyph)
        dos.writeShort(xAdvance)
        return out.toByteArray()
    }

    private fun buildGposPairPosFormat2(): ByteArray {
        // PairPos Format 2 class kerning: glyph 1 in Class1=1, glyph 3 ('T') in Class2=1 with XAdvance = -150
        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)
        dos.writeShort(1)
        dos.writeShort(0)
        dos.writeShort(0)
        dos.writeShort(0)
        dos.writeShort(10)

        // LookupList at 10
        dos.writeShort(1)
        dos.writeShort(4) // -> Lookup at 14

        // Lookup at 14
        dos.writeShort(2)
        dos.writeShort(0)
        dos.writeShort(1)
        dos.writeShort(8) // -> Subtable at 22

        // PairPosFormat2 at 22:
        // header is 16 bytes + class1Count(2) * class2Count(2) * 2 bytes = 16 + 8 = 24 bytes (ends at 46, rel +24)
        dos.writeShort(2) // posFormat=2
        dos.writeShort(24) // coverageOff -> +24
        dos.writeShort(0x0004) // valueFormat1 = XAdvance (2 bytes)
        dos.writeShort(0) // valueFormat2 = 0
        dos.writeShort(30) // classDef1Off -> +30
        dos.writeShort(38) // classDef2Off -> +38
        dos.writeShort(2) // class1Count = 2 (0, 1)
        dos.writeShort(2) // class2Count = 2 (0, 1)
        // Matrix [0,0], [0,1], [1,0], [1,1]:
        dos.writeShort(0)
        dos.writeShort(0)
        dos.writeShort(0)
        dos.writeShort(-150) // class1=1, class2=1 -> -150 design units

        // Coverage Format 1 at rel +24 (6 bytes: +24..+30): covers glyph 1 ('A')
        dos.writeShort(1)
        dos.writeShort(1)
        dos.writeShort(1)

        // ClassDef1 Format 1 at rel +30 (8 bytes: +30..+38): startGlyph=1, count=1, class=[1]
        dos.writeShort(1)
        dos.writeShort(1)
        dos.writeShort(1)
        dos.writeShort(1)

        // ClassDef2 Format 2 at rel +38 (10 bytes): format=2, rangeCount=1, [start=3, end=3, class=1]
        dos.writeShort(2)
        dos.writeShort(1)
        dos.writeShort(3)
        dos.writeShort(3)
        dos.writeShort(1)

        return out.toByteArray()
    }

    private fun putU16(b: ByteArray, offset: Int, v: Int) {
        b[offset] = ((v ushr 8) and 0xFF).toByte()
        b[offset + 1] = (v and 0xFF).toByte()
    }

    private fun putI16(b: ByteArray, offset: Int, v: Int) = putU16(b, offset, v)
}

class OpenTypeFontTest {

    @Test
    fun `parses static TrueType and OpenType CFF fonts including Plane 15 cmap Format 12 and GPOS class kerning`() {
        val parsed = OpenTypeFont.parse(
            SfntFixtures.buildStaticFont(cff = true, includeGposClassKern = true),
        )
        val info = parsed.info
        assertEquals("Glyphsmith Sans", info.familyName)
        assertEquals(OutlineFormat.CFF, info.outlineFormat)
        assertFalse(info.isVariableFont)
        assertEquals(1000, info.unitsPerEm)
        assertEquals(800, info.ascent)
        assertEquals(-200, info.descent)
        assertEquals(4, info.mappedCodepointCount)
        assertEquals(600, parsed.advanceUnitsForCodepoint(0xF0010))

        // 'A' + 'V' came from kern (-120), while 'A' + 'T' was superseded by GPOS Format 2 (-150)
        val av = info.kerningPairs.single { it.leftCodepoint == 'A'.code && it.rightCodepoint == 'V'.code }
        assertEquals(-120, av.designUnits)
        assertEquals("kern", av.source)

        val at = info.kerningPairs.single { it.leftCodepoint == 'A'.code && it.rightCodepoint == 'T'.code }
        assertEquals(-150, at.designUnits)
        assertEquals("GPOS", at.source)
    }

    @Test
    fun `extracts fvar axes, avar warps, STAT design axes, named instances, and GPOS PairPos Format 1`(@TempDir dir: Path) {
        val vfBytes = SfntFixtures.buildVariableFont()
        val vfPath = dir.resolve("InterPixel-VF.ttf")
        Files.write(vfPath, vfBytes)

        val parsed = OpenTypeFont.read(vfPath)
        val info = parsed.info
        assertTrue(info.isVariableFont)
        assertEquals(2, info.variationAxes.size)
        assertEquals(2, info.statDesignAxisCount)
        assertTrue(info.variationPayloadBytes > 512L)

        val wght = info.variationAxes[0]
        assertEquals("wght", wght.tag)
        assertEquals("Weight", wght.name)
        assertEquals(100.0, wght.minValue)
        assertEquals(400.0, wght.defaultValue)
        assertEquals(900.0, wght.maxValue)
        assertFalse(wght.hidden)
        assertEquals(3, wght.avarSegmentPoints)

        val wdth = info.variationAxes[1]
        assertEquals("wdth", wdth.tag)
        assertTrue(wdth.hidden)

        assertEquals(2, info.namedInstances.size)
        assertEquals("Bold", info.namedInstances[1].name)
        assertEquals("InterPixelVF-Bold", info.namedInstances[1].postScriptName)
        assertEquals(700.0, info.namedInstances[1].coordinates["wght"])

        val gposPair = info.kerningPairs.single()
        assertEquals('A'.code, gposPair.leftCodepoint)
        assertEquals('V'.code, gposPair.rightCodepoint)
        assertEquals(-110, gposPair.designUnits)
        assertEquals("GPOS", gposPair.source)

        // Verify TTC collection wrapper parsing
        val ttc = OpenTypeFont.parse(SfntFixtures.wrapInTtc(vfBytes))
        assertTrue(ttc.info.isCollection)
        assertEquals(1, ttc.info.collectionFontCount)
        assertEquals("Inter Pixel VF", ttc.info.familyName)

        // Verify TtfCommand --json CLI round-trip
        val cli = GlyphsmithCli().subcommands(TtfCommand())
        cli.parse(listOf("ttf", vfPath.toString(), "--size", "11.0", "--json"))
        val jsonElem = Json.parseToJsonElement(info.toJsonString()).jsonObject
        assertEquals(2, jsonElem["variationAxes"]!!.jsonArray.size)
        assertEquals(1000, jsonElem["unitsPerEm"]!!.jsonPrimitive.int)
    }

    @Test
    fun `rejects WOFF, WOFF2, and truncated SFNT containers with TTF_UNSUPPORTED_CONTAINER`() {
        val woff2 = "wOF2".toByteArray(StandardCharsets.US_ASCII) + ByteArray(32)
        val ex1 = assertThrows<OpenTypeException> { OpenTypeFont.parse(woff2) }
        assertEquals("TTF_UNSUPPORTED_CONTAINER", ex1.errorCode)
        assertTrue(ex1.message.contains("WOFF2"))

        val woff = "wOFF".toByteArray(StandardCharsets.US_ASCII) + ByteArray(32)
        val ex2 = assertThrows<OpenTypeException> { OpenTypeFont.parse(woff) }
        assertEquals("TTF_UNSUPPORTED_CONTAINER", ex2.errorCode)

        val truncated = ByteArray(6)
        val ex3 = assertThrows<OpenTypeException> { OpenTypeFont.parse(truncated) }
        assertEquals("TTF_UNSUPPORTED_CONTAINER", ex3.errorCode)
    }
}
