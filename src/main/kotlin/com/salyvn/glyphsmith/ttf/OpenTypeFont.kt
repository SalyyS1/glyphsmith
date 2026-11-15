package com.salyvn.glyphsmith.ttf

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

enum class OutlineFormat {
    TRUETYPE,
    CFF,
}

class OpenTypeException(
    val errorCode: String,
    override val message: String,
) : Exception(message)

@Serializable
data class VariationAxis(
    val tag: String,
    val name: String,
    val minValue: Double,
    val defaultValue: Double,
    val maxValue: Double,
    val hidden: Boolean,
    val avarSegmentPoints: Int = 0,
)

@Serializable
data class NamedInstance(
    val name: String,
    val postScriptName: String? = null,
    val coordinates: Map<String, Double>,
)

@Serializable
data class OpenTypeKerningPair(
    val leftCodepoint: Int,
    val rightCodepoint: Int,
    val leftGlyph: Int,
    val rightGlyph: Int,
    val designUnits: Int,
    val source: String,
)

@Serializable
data class OpenTypeFontInfo(
    val familyName: String,
    val subfamilyName: String,
    val fullName: String,
    val outlineFormat: OutlineFormat,
    val isCollection: Boolean,
    val collectionFontCount: Int,
    val unitsPerEm: Int,
    val ascent: Int,
    val descent: Int,
    val lineGap: Int,
    val xMin: Int,
    val yMin: Int,
    val xMax: Int,
    val yMax: Int,
    val numGlyphs: Int,
    val mappedCodepointCount: Int,
    val tables: List<String>,
    val variationAxes: List<VariationAxis>,
    val namedInstances: List<NamedInstance>,
    val statDesignAxisCount: Int,
    val variationPayloadBytes: Long,
    val kerningPairs: List<OpenTypeKerningPair>,
) {
    val isVariableFont: Boolean get() = variationAxes.isNotEmpty()

    fun toJsonString(): String = JSON_OUT.encodeToString(this)

    companion object {
        private val JSON_OUT = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
            encodeDefaults = true
        }
    }
}

/**
 * Parsed representation of a TrueType or OpenType SFNT font binary (`.ttf`, `.otf`, or `.ttc`).
 *
 * Minecraft's `TrueTypeGlyphProviderDefinition` passes the font file directly to FreeType:
 * 1. Compressed web containers (`wOFF` and `wOF2`) are rejected at load time.
 * 2. OpenType variable fonts (`fvar` / `gvar` / `CFF2`) load only at their default axis coordinates because
 *    the provider schema exposes no variation-coordinate fields.
 * 3. Design-unit metrics (`unitsPerEm`, `hmtx` advances, `kern` and `GPOS` pair adjustments) determine how
 *    glyphs quantize onto Minecraft's GUI pixel grid at a target `size`.
 */
class OpenTypeFont(
    val info: OpenTypeFontInfo,
    val codepointToGlyph: Map<Int, Int>,
    val glyphAdvances: IntArray,
    val glyphLeftSideBearings: IntArray,
) {
    fun advanceUnitsForCodepoint(codepoint: Int): Int? {
        val glyphId = codepointToGlyph[codepoint] ?: return null
        return if (glyphId in glyphAdvances.indices) glyphAdvances[glyphId] else null
    }

    companion object {
        private const val MAGIC_TRUETYPE_1 = 0x00010000
        private const val MAGIC_TRUETYPE_TRUE = 0x74727565 // 'true'
        private const val MAGIC_OTTO = 0x4F54544F // 'OTTO'
        private const val MAGIC_TTCF = 0x74746366 // 'ttcf'
        private const val MAGIC_WOFF = 0x774F4646 // 'wOFF'
        private const val MAGIC_WOFF2 = 0x774F4632 // 'wOF2'

        private val VARIATION_TABLES = setOf("fvar", "gvar", "HVAR", "VVAR", "MVAR", "STAT", "avar", "CFF2")

        private data class TableRecord(
            val tag: String,
            val offset: Int,
            val length: Int,
        )

        fun read(path: Path): OpenTypeFont {
            if (!Files.isRegularFile(path)) {
                throw IOException("No such TrueType/OpenType font file: $path")
            }
            return parse(Files.readAllBytes(path))
        }

        fun parse(bytes: ByteArray): OpenTypeFont {
            if (bytes.size < 12) {
                throw OpenTypeException(
                    "TTF_UNSUPPORTED_CONTAINER",
                    "Font binary is truncated (${bytes.size} bytes; minimum SFNT header is 12 bytes).",
                )
            }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val topMagic = buf.getInt(0)
            when (topMagic) {
                MAGIC_WOFF -> throw OpenTypeException(
                    "TTF_UNSUPPORTED_CONTAINER",
                    "Font uses WOFF web compression ('wOFF'); Minecraft FreeType requires uncompressed .ttf or .otf.",
                )
                MAGIC_WOFF2 -> throw OpenTypeException(
                    "TTF_UNSUPPORTED_CONTAINER",
                    "Font uses WOFF2 Brotli compression ('wOF2'); Minecraft FreeType requires uncompressed .ttf or .otf.",
                )
            }

            val isCollection: Boolean
            val collectionCount: Int
            val sfntOffset: Int

            if (topMagic == MAGIC_TTCF) {
                if (bytes.size < 16) {
                    throw OpenTypeException("TTF_UNSUPPORTED_CONTAINER", "Truncated TrueType Collection ('ttcf') header.")
                }
                isCollection = true
                collectionCount = buf.u32(8).coerceAtLeast(0).toInt()
                if (collectionCount < 1) {
                    throw OpenTypeException("TTF_UNSUPPORTED_CONTAINER", "TrueType Collection ('ttcf') contains zero font faces.")
                }
                val firstFaceOffset = buf.u32(12)
                if (firstFaceOffset < 12 || firstFaceOffset + 12 > bytes.size) {
                    throw OpenTypeException("TTF_UNSUPPORTED_CONTAINER", "TrueType Collection ('ttcf') first face offset is out of bounds.")
                }
                sfntOffset = firstFaceOffset.toInt()
            } else {
                isCollection = false
                collectionCount = 1
                sfntOffset = 0
            }

            val sfntMagic = buf.getInt(sfntOffset)
            val outlineFormat = when (sfntMagic) {
                MAGIC_TRUETYPE_1, MAGIC_TRUETYPE_TRUE -> OutlineFormat.TRUETYPE
                MAGIC_OTTO -> OutlineFormat.CFF
                else -> throw OpenTypeException(
                    "TTF_UNSUPPORTED_CONTAINER",
                    "Unrecognized SFNT signature 0x%08X (expected TrueType 0x00010000 or OpenType 'OTTO').".format(sfntMagic),
                )
            }

            val numTables = buf.u16(sfntOffset + 4)
            if (numTables <= 0 || sfntOffset + 12L + numTables * 16L > bytes.size) {
                throw OpenTypeException(
                    "TTF_UNSUPPORTED_CONTAINER",
                    "Malformed SFNT table directory (numTables=$numTables exceeds ${bytes.size}-byte file).",
                )
            }

            val tables = LinkedHashMap<String, TableRecord>()
            for (i in 0 until numTables) {
                val recOff = sfntOffset + 12 + i * 16
                val tag = buf.ascii(recOff, 4)
                val offset = buf.u32(recOff + 8)
                val length = buf.u32(recOff + 12)
                if (offset < 0 || length < 0 || offset + length > bytes.size) {
                    throw OpenTypeException(
                        "TTF_UNSUPPORTED_CONTAINER",
                        "SFNT table '$tag' (offset=$offset, length=$length) points outside ${bytes.size}-byte font binary.",
                    )
                }
                tables[tag] = TableRecord(tag, offset.toInt(), length.toInt())
            }

            val head = tables["head"] ?: throw OpenTypeException("TTF_UNSUPPORTED_CONTAINER", "Font is missing required SFNT 'head' table.")
            val maxp = tables["maxp"] ?: throw OpenTypeException("TTF_UNSUPPORTED_CONTAINER", "Font is missing required SFNT 'maxp' table.")
            val hhea = tables["hhea"] ?: throw OpenTypeException("TTF_UNSUPPORTED_CONTAINER", "Font is missing required SFNT 'hhea' table.")
            val hmtx = tables["hmtx"] ?: throw OpenTypeException("TTF_UNSUPPORTED_CONTAINER", "Font is missing required SFNT 'hmtx' table.")
            val cmap = tables["cmap"] ?: throw OpenTypeException("TTF_UNSUPPORTED_CONTAINER", "Font is missing required SFNT 'cmap' table.")

            if (head.length < 54) {
                throw OpenTypeException("TTF_UNSUPPORTED_CONTAINER", "SFNT 'head' table is truncated (${head.length} < 54 bytes).")
            }
            val unitsPerEm = buf.u16(head.offset + 18)
            if (unitsPerEm !in 16..16384) {
                throw OpenTypeException(
                    "TTF_UNSUPPORTED_CONTAINER",
                    "SFNT 'head.unitsPerEm' is $unitsPerEm (OpenType specification requires 16..16384).",
                )
            }
            val xMin = buf.i16(head.offset + 36)
            val yMin = buf.i16(head.offset + 38)
            val xMax = buf.i16(head.offset + 40)
            val yMax = buf.i16(head.offset + 42)

            if (maxp.length < 6) {
                throw OpenTypeException("TTF_UNSUPPORTED_CONTAINER", "SFNT 'maxp' table is truncated (${maxp.length} < 6 bytes).")
            }
            val numGlyphs = buf.u16(maxp.offset + 4)
            if (numGlyphs <= 0) {
                throw OpenTypeException("TTF_UNSUPPORTED_CONTAINER", "SFNT 'maxp' declares 0 glyphs.")
            }

            if (hhea.length < 36) {
                throw OpenTypeException("TTF_UNSUPPORTED_CONTAINER", "SFNT 'hhea' table is truncated (${hhea.length} < 36 bytes).")
            }
            val ascent = buf.i16(hhea.offset + 4)
            val descent = buf.i16(hhea.offset + 6)
            val lineGap = buf.i16(hhea.offset + 8)
            val numHMetrics = buf.u16(hhea.offset + 34)
            if (numHMetrics <= 0 || numHMetrics > numGlyphs) {
                throw OpenTypeException(
                    "TTF_UNSUPPORTED_CONTAINER",
                    "SFNT 'hhea.numberOfHMetrics' ($numHMetrics) is invalid for numGlyphs=$numGlyphs.",
                )
            }

            val expectedHmtxMin = numHMetrics * 4 + (numGlyphs - numHMetrics) * 2
            if (hmtx.length < expectedHmtxMin) {
                throw OpenTypeException(
                    "TTF_UNSUPPORTED_CONTAINER",
                    "SFNT 'hmtx' table is truncated (${hmtx.length} < $expectedHmtxMin bytes).",
                )
            }
            val advances = IntArray(numGlyphs)
            val lsbs = IntArray(numGlyphs)
            var lastAdvance = 0
            for (g in 0 until numHMetrics) {
                val pos = hmtx.offset + g * 4
                lastAdvance = buf.u16(pos)
                advances[g] = lastAdvance
                lsbs[g] = buf.i16(pos + 2)
            }
            val lsbTailBase = hmtx.offset + numHMetrics * 4
            for (g in numHMetrics until numGlyphs) {
                advances[g] = lastAdvance
                lsbs[g] = buf.i16(lsbTailBase + (g - numHMetrics) * 2)
            }

            val names = tables["name"]?.let { parseNameTable(buf, it) } ?: emptyMap()
            val family = names[16] ?: names[1] ?: "Unknown"
            val subfamily = names[17] ?: names[2] ?: "Regular"
            val fullName = names[4] ?: "$family $subfamily".trim()

            val cpToGlyph = parseCmap(buf, cmap, numGlyphs)
            val avarCounts = tables["avar"]?.let { parseAvarSegmentCounts(buf, it) } ?: emptyList()
            val (axes, instances) = tables["fvar"]?.let { parseFvar(buf, it, names, avarCounts) } ?: (emptyList<VariationAxis>() to emptyList())
            val statAxes = tables["STAT"]?.let { parseStatDesignAxisCount(buf, it) } ?: 0
            val variationBytes = tables.values
                .filter { it.tag in VARIATION_TABLES }
                .sumOf { it.length.toLong() }

            // Build canonical glyphId -> preferred Unicode codepoint lookup for kerning pair reporting.
            val glyphToCp = HashMap<Int, Int>()
            for ((cp, gid) in cpToGlyph) {
                val existing = glyphToCp[gid]
                if (existing == null || preferCodepoint(cp, existing)) {
                    glyphToCp[gid] = cp
                }
            }

            val kerningByPair = LinkedHashMap<Pair<Int, Int>, OpenTypeKerningPair>()
            tables["kern"]?.let { kernRec ->
                for (pair in parseKernTable(buf, kernRec, glyphToCp)) {
                    kerningByPair[pair.leftGlyph to pair.rightGlyph] = pair
                }
            }
            tables["GPOS"]?.let { gposRec ->
                for (pair in parseGposPairPos(buf, gposRec, glyphToCp)) {
                    // GPOS supersedes legacy kern when both exist for the same glyph pair.
                    kerningByPair[pair.leftGlyph to pair.rightGlyph] = pair
                }
            }

            val info = OpenTypeFontInfo(
                familyName = family,
                subfamilyName = subfamily,
                fullName = fullName,
                outlineFormat = outlineFormat,
                isCollection = isCollection,
                collectionFontCount = collectionCount,
                unitsPerEm = unitsPerEm,
                ascent = ascent,
                descent = descent,
                lineGap = lineGap,
                xMin = xMin,
                yMin = yMin,
                xMax = xMax,
                yMax = yMax,
                numGlyphs = numGlyphs,
                mappedCodepointCount = cpToGlyph.size,
                tables = tables.keys.toList(),
                variationAxes = axes,
                namedInstances = instances,
                statDesignAxisCount = statAxes,
                variationPayloadBytes = variationBytes,
                kerningPairs = kerningByPair.values.toList(),
            )
            return OpenTypeFont(
                info = info,
                codepointToGlyph = cpToGlyph,
                glyphAdvances = advances,
                glyphLeftSideBearings = lsbs,
            )
        }

        private fun preferCodepoint(candidate: Int, current: Int): Boolean {
            val candPrintableAscii = candidate in 0x20..0x7E
            val currPrintableAscii = current in 0x20..0x7E
            if (candPrintableAscii != currPrintableAscii) return candPrintableAscii
            return candidate < current
        }

        private fun parseNameTable(buf: ByteBuffer, rec: TableRecord): Map<Int, String> {
            if (rec.length < 6) return emptyMap()
            val count = buf.u16(rec.offset + 2)
            val stringStorageOffset = rec.offset + buf.u16(rec.offset + 4)
            val endLimit = rec.offset + rec.length
            val bestScore = HashMap<Int, Int>()
            val result = HashMap<Int, String>()

            for (i in 0 until count) {
                val entryOff = rec.offset + 6 + i * 12
                if (entryOff + 12 > endLimit) break
                val platformId = buf.u16(entryOff)
                val encodingId = buf.u16(entryOff + 2)
                val languageId = buf.u16(entryOff + 4)
                val nameId = buf.u16(entryOff + 6)
                val strLen = buf.u16(entryOff + 8)
                val strRel = buf.u16(entryOff + 10)
                val absStart = stringStorageOffset + strRel
                if (absStart < rec.offset || absStart + strLen > endLimit) continue

                val score = when {
                    platformId == 3 && (encodingId == 1 || encodingId == 10) && languageId == 0x0409 -> 4
                    platformId == 3 && (encodingId == 1 || encodingId == 10) -> 3
                    platformId == 0 -> 2
                    platformId == 1 && encodingId == 0 -> 1
                    else -> 0
                }
                if (score == 0 || score <= (bestScore[nameId] ?: 0)) continue

                val slice = ByteArray(strLen)
                for (b in 0 until strLen) slice[b] = buf.get(absStart + b)
                val text = if (platformId == 1) {
                    String(slice, StandardCharsets.ISO_8859_1)
                } else {
                    String(slice, StandardCharsets.UTF_16BE)
                }.trim { it <= ' ' || it == '\u0000' }

                if (text.isNotEmpty()) {
                    bestScore[nameId] = score
                    result[nameId] = text
                }
            }
            return result
        }

        private fun parseCmap(buf: ByteBuffer, rec: TableRecord, numGlyphs: Int): Map<Int, Int> {
            if (rec.length < 4) return emptyMap()
            val endLimit = rec.offset + rec.length
            val numSubtables = buf.u16(rec.offset + 2)
            var format12Offset = -1
            var format4Offset = -1

            for (i in 0 until numSubtables) {
                val subRec = rec.offset + 4 + i * 8
                if (subRec + 8 > endLimit) break
                val subOff = rec.offset + buf.u32(subRec + 4).toInt()
                if (subOff < rec.offset || subOff + 4 > endLimit) continue
                when (buf.u16(subOff)) {
                    12 -> if (format12Offset < 0) format12Offset = subOff
                    4 -> if (format4Offset < 0) format4Offset = subOff
                }
            }

            val map = LinkedHashMap<Int, Int>()
            if (format12Offset >= 0) {
                parseCmapFormat12(buf, format12Offset, endLimit, numGlyphs, map)
            } else if (format4Offset >= 0) {
                parseCmapFormat4(buf, format4Offset, endLimit, numGlyphs, map)
            }
            return map
        }

        private fun parseCmapFormat12(
            buf: ByteBuffer,
            subOff: Int,
            endLimit: Int,
            numGlyphs: Int,
            out: MutableMap<Int, Int>,
        ) {
            if (subOff + 16 > endLimit) return
            val numGroups = buf.u32(subOff + 12).coerceAtMost(65_536).toInt()
            for (g in 0 until numGroups) {
                val gOff = subOff + 16 + g * 12
                if (gOff + 12 > endLimit) break
                val startCp = buf.u32(gOff)
                val endCp = buf.u32(gOff + 4)
                val startGlyph = buf.u32(gOff + 8)
                if (startCp > endCp || endCp > 0x10FFFFL) continue
                // Cap per-group expansion against pathological malformed ranges.
                val span = (endCp - startCp).coerceAtMost(131_072L).toInt()
                for (delta in 0..span) {
                    val cp = (startCp + delta).toInt()
                    val gid = (startGlyph + delta).toInt()
                    if (gid in 1 until numGlyphs) {
                        out[cp] = gid
                    }
                }
            }
        }

        private fun parseCmapFormat4(
            buf: ByteBuffer,
            subOff: Int,
            endLimit: Int,
            numGlyphs: Int,
            out: MutableMap<Int, Int>,
        ) {
            if (subOff + 16 > endLimit) return
            val subLength = buf.u16(subOff + 2)
            val subEnd = (subOff + subLength).coerceAtMost(endLimit)
            val segCountX2 = buf.u16(subOff + 6)
            if (segCountX2 < 2 || segCountX2 % 2 != 0) return
            val segCount = segCountX2 / 2
            val endCodeBase = subOff + 14
            val startCodeBase = endCodeBase + segCountX2 + 2
            val idDeltaBase = startCodeBase + segCountX2
            val idRangeOffsetBase = idDeltaBase + segCountX2
            if (idRangeOffsetBase + segCountX2 > subEnd) return

            for (s in 0 until segCount) {
                val endCode = buf.u16(endCodeBase + s * 2)
                val startCode = buf.u16(startCodeBase + s * 2)
                val idDelta = buf.i16(idDeltaBase + s * 2)
                val idRangeOffsetPos = idRangeOffsetBase + s * 2
                val idRangeOffset = buf.u16(idRangeOffsetPos)
                if (startCode == 0xFFFF && endCode == 0xFFFF) break
                if (startCode > endCode) continue

                for (cp in startCode..endCode) {
                    val gid = if (idRangeOffset == 0) {
                        (cp + idDelta) and 0xFFFF
                    } else {
                        val glyphIdxAddr = idRangeOffsetPos + idRangeOffset + 2 * (cp - startCode)
                        if (glyphIdxAddr + 2 > subEnd) continue
                        val rawGid = buf.u16(glyphIdxAddr)
                        if (rawGid == 0) 0 else (rawGid + idDelta) and 0xFFFF
                    }
                    if (gid in 1 until numGlyphs) {
                        out[cp] = gid
                    }
                }
            }
        }

        private fun parseAvarSegmentCounts(buf: ByteBuffer, rec: TableRecord): List<Int> {
            if (rec.length < 8) return emptyList()
            val endLimit = rec.offset + rec.length
            val major = buf.u16(rec.offset)
            if (major != 1) return emptyList()
            val axisCount = buf.u16(rec.offset + 6)
            val counts = ArrayList<Int>(axisCount)
            var ptr = rec.offset + 8
            for (i in 0 until axisCount) {
                if (ptr + 2 > endLimit) break
                val pairCount = buf.u16(ptr)
                counts += pairCount
                ptr += 2 + pairCount * 4
            }
            return counts
        }

        private fun parseStatDesignAxisCount(buf: ByteBuffer, rec: TableRecord): Int {
            if (rec.length < 10) return 0
            val major = buf.u16(rec.offset)
            if (major != 1) return 0
            return buf.u16(rec.offset + 6)
        }

        private fun parseFvar(
            buf: ByteBuffer,
            rec: TableRecord,
            names: Map<Int, String>,
            avarCounts: List<Int>,
        ): Pair<List<VariationAxis>, List<NamedInstance>> {
            if (rec.length < 16) return emptyList<VariationAxis>() to emptyList()
            val endLimit = rec.offset + rec.length
            val major = buf.u16(rec.offset)
            if (major != 1) return emptyList<VariationAxis>() to emptyList()
            val axesArrayOffset = buf.u16(rec.offset + 4)
            val axisCount = buf.u16(rec.offset + 8)
            val axisSize = buf.u16(rec.offset + 10)
            val instanceCount = buf.u16(rec.offset + 12)
            val instanceSize = buf.u16(rec.offset + 14)
            if (axisCount == 0 || axisSize < 20) return emptyList<VariationAxis>() to emptyList()

            val axes = ArrayList<VariationAxis>(axisCount)
            var ptr = rec.offset + axesArrayOffset
            for (a in 0 until axisCount) {
                if (ptr + axisSize > endLimit) break
                val tag = buf.ascii(ptr, 4)
                val minVal = buf.fixed1616(ptr + 4)
                val defVal = buf.fixed1616(ptr + 8)
                val maxVal = buf.fixed1616(ptr + 12)
                val flags = buf.u16(ptr + 16)
                val axisNameId = buf.u16(ptr + 18)
                val axisName = names[axisNameId] ?: defaultAxisName(tag)
                axes += VariationAxis(
                    tag = tag,
                    name = axisName,
                    minValue = minVal,
                    defaultValue = defVal,
                    maxValue = maxVal,
                    hidden = (flags and 0x0001) != 0,
                    avarSegmentPoints = avarCounts.getOrElse(a) { 0 },
                )
                ptr += axisSize
            }

            val instances = ArrayList<NamedInstance>(instanceCount)
            val minInstanceBytes = 4 + axes.size * 4
            if (instanceSize >= minInstanceBytes) {
                for (j in 0 until instanceCount) {
                    if (ptr + instanceSize > endLimit) break
                    val subfamilyNameId = buf.u16(ptr)
                    val coords = LinkedHashMap<String, Double>(axes.size)
                    for (a in axes.indices) {
                        coords[axes[a].tag] = buf.fixed1616(ptr + 4 + a * 4)
                    }
                    val psName = if (instanceSize >= minInstanceBytes + 2) {
                        val psId = buf.u16(ptr + minInstanceBytes)
                        if (psId != 0xFFFF && psId != 0) names[psId] else null
                    } else {
                        null
                    }
                    val instName = names[subfamilyNameId] ?: "Instance #${j + 1}"
                    instances += NamedInstance(
                        name = instName,
                        postScriptName = psName,
                        coordinates = coords,
                    )
                    ptr += instanceSize
                }
            }
            return axes to instances
        }

        private fun defaultAxisName(tag: String): String = when (tag) {
            "wght" -> "Weight"
            "wdth" -> "Width"
            "slnt" -> "Slant"
            "ital" -> "Italic"
            "opsz" -> "Optical Size"
            else -> tag
        }

        private fun parseKernTable(
            buf: ByteBuffer,
            rec: TableRecord,
            glyphToCp: Map<Int, Int>,
        ): List<OpenTypeKerningPair> {
            if (rec.length < 4) return emptyList()
            val endLimit = rec.offset + rec.length
            val out = ArrayList<OpenTypeKerningPair>()
            val firstWord = buf.u16(rec.offset)

            if (firstWord == 0) {
                // OpenType / Windows kern v0 header: uint16 version=0, uint16 nTables
                val nTables = buf.u16(rec.offset + 2)
                var subOff = rec.offset + 4
                for (t in 0 until nTables) {
                    if (subOff + 6 > endLimit) break
                    val length = buf.u16(subOff + 2)
                    val coverage = buf.u16(subOff + 4)
                    if (length < 14 || subOff + length > endLimit) break
                    val format = coverage ushr 8
                    val horizontal = (coverage and 0x0001) != 0
                    val crossStream = (coverage and 0x0004) != 0
                    if (format == 0 && horizontal && !crossStream) {
                        val nPairs = buf.u16(subOff + 6)
                        var pOff = subOff + 14
                        for (p in 0 until nPairs) {
                            if (pOff + 6 > subOff + length) break
                            val leftGid = buf.u16(pOff)
                            val rightGid = buf.u16(pOff + 2)
                            val value = buf.i16(pOff + 4)
                            if (value != 0) {
                                val lCp = glyphToCp[leftGid]
                                val rCp = glyphToCp[rightGid]
                                if (lCp != null && rCp != null) {
                                    out += OpenTypeKerningPair(lCp, rCp, leftGid, rightGid, value, "kern")
                                }
                            }
                            pOff += 6
                        }
                    }
                    subOff += length
                }
            } else if (rec.length >= 8 && buf.getInt(rec.offset) == 0x00010000) {
                // Apple AAT kern v1.0 header: uint32 version=0x00010000, uint32 nTables
                val nTables = buf.u32(rec.offset + 4).coerceAtMost(256).toInt()
                var subOff = rec.offset + 8
                for (t in 0 until nTables) {
                    if (subOff + 8 > endLimit) break
                    val length = buf.u32(subOff).toInt()
                    val coverage = buf.u16(subOff + 4)
                    if (length < 16 || subOff + length > endLimit) break
                    val format = coverage and 0x00FF
                    val vertical = (coverage and 0x8000) != 0
                    val crossStream = (coverage and 0x4000) != 0
                    if (format == 0 && !vertical && !crossStream) {
                        val nPairs = buf.u16(subOff + 8)
                        var pOff = subOff + 16
                        for (p in 0 until nPairs) {
                            if (pOff + 6 > subOff + length) break
                            val leftGid = buf.u16(pOff)
                            val rightGid = buf.u16(pOff + 2)
                            val value = buf.i16(pOff + 4)
                            if (value != 0) {
                                val lCp = glyphToCp[leftGid]
                                val rCp = glyphToCp[rightGid]
                                if (lCp != null && rCp != null) {
                                    out += OpenTypeKerningPair(lCp, rCp, leftGid, rightGid, value, "kern")
                                }
                            }
                            pOff += 6
                        }
                    }
                    subOff += length
                }
            }
            return out
        }

        private fun parseGposPairPos(
            buf: ByteBuffer,
            rec: TableRecord,
            glyphToCp: Map<Int, Int>,
        ): List<OpenTypeKerningPair> {
            if (rec.length < 10) return emptyList()
            val endLimit = rec.offset + rec.length
            val major = buf.u16(rec.offset)
            if (major != 1) return emptyList()
            val lookupListRel = buf.u16(rec.offset + 8)
            if (lookupListRel == 0) return emptyList()
            val lookupListOff = rec.offset + lookupListRel
            if (lookupListOff + 2 > endLimit) return emptyList()

            val lookupCount = buf.u16(lookupListOff)
            val out = ArrayList<OpenTypeKerningPair>()

            for (l in 0 until lookupCount) {
                val offPtr = lookupListOff + 2 + l * 2
                if (offPtr + 2 > endLimit) break
                val lookupOff = lookupListOff + buf.u16(offPtr)
                if (lookupOff + 6 > endLimit) continue
                val lookupType = buf.u16(lookupOff)
                val subTableCount = buf.u16(lookupOff + 4)

                for (s in 0 until subTableCount) {
                    val subPtr = lookupOff + 6 + s * 2
                    if (subPtr + 2 > endLimit) break
                    var subOff = lookupOff + buf.u16(subPtr)
                    if (subOff + 6 > endLimit) continue
                    var effectiveType = lookupType

                    if (effectiveType == 9) {
                        // ExtensionPos Format 1: posFormat=1, extensionLookupType, uint32 extensionOffset
                        if (subOff + 8 > endLimit || buf.u16(subOff) != 1) continue
                        effectiveType = buf.u16(subOff + 2)
                        val extRel = buf.u32(subOff + 4)
                        if (extRel <= 0 || subOff + extRel > endLimit) continue
                        subOff = (subOff + extRel).toInt()
                    }
                    if (effectiveType != 2 || subOff + 10 > endLimit) continue

                    when (buf.u16(subOff)) {
                        1 -> parsePairPosFormat1(buf, subOff, endLimit, glyphToCp, out)
                        2 -> parsePairPosFormat2(buf, subOff, endLimit, glyphToCp, out)
                    }
                }
            }
            return out
        }

        private fun parsePairPosFormat1(
            buf: ByteBuffer,
            subOff: Int,
            endLimit: Int,
            glyphToCp: Map<Int, Int>,
            out: MutableList<OpenTypeKerningPair>,
        ) {
            val coverageOff = subOff + buf.u16(subOff + 2)
            val valueFormat1 = buf.u16(subOff + 4)
            val valueFormat2 = buf.u16(subOff + 6)
            val pairSetCount = buf.u16(subOff + 8)
            val vf1Bytes = valueRecordSize(valueFormat1)
            val vf2Bytes = valueRecordSize(valueFormat2)
            val entryBytes = 2 + vf1Bytes + vf2Bytes
            val firstGlyphs = parseCoverage(buf, coverageOff, endLimit)

            val count = minOf(pairSetCount, firstGlyphs.size)
            for (i in 0 until count) {
                val ptr = subOff + 10 + i * 2
                if (ptr + 2 > endLimit) break
                val pairSetOff = subOff + buf.u16(ptr)
                if (pairSetOff + 2 > endLimit) continue
                val leftGid = firstGlyphs[i]
                val lCp = glyphToCp[leftGid] ?: continue
                val pairValueCount = buf.u16(pairSetOff)
                for (p in 0 until pairValueCount) {
                    val recOff = pairSetOff + 2 + p * entryBytes
                    if (recOff + entryBytes > endLimit) break
                    val rightGid = buf.u16(recOff)
                    val rCp = glyphToCp[rightGid] ?: continue
                    val xAdv = readValueRecordXAdvance(buf, recOff + 2, valueFormat1)
                    if (xAdv != 0) {
                        out += OpenTypeKerningPair(lCp, rCp, leftGid, rightGid, xAdv, "GPOS")
                    }
                }
            }
        }

        private fun parsePairPosFormat2(
            buf: ByteBuffer,
            subOff: Int,
            endLimit: Int,
            glyphToCp: Map<Int, Int>,
            out: MutableList<OpenTypeKerningPair>,
        ) {
            if (subOff + 16 > endLimit) return
            val coverageOff = subOff + buf.u16(subOff + 2)
            val valueFormat1 = buf.u16(subOff + 4)
            val valueFormat2 = buf.u16(subOff + 6)
            val classDef1Off = subOff + buf.u16(subOff + 8)
            val classDef2Off = subOff + buf.u16(subOff + 10)
            val class1Count = buf.u16(subOff + 12)
            val class2Count = buf.u16(subOff + 14)

            val vf1Bytes = valueRecordSize(valueFormat1)
            val vf2Bytes = valueRecordSize(valueFormat2)
            val class2RecBytes = vf1Bytes + vf2Bytes
            val class1RecBytes = class2Count * class2RecBytes
            if (subOff + 16L + class1Count.toLong() * class1RecBytes > endLimit) return

            val coveredLeft = parseCoverage(buf, coverageOff, endLimit).toHashSet()
            val class1Map = parseClassDef(buf, classDef1Off, endLimit)
            val class2Map = parseClassDef(buf, classDef2Off, endLimit)

            // Group mapped codepoints' glyphs by class1 and class2 (capped per class to keep matrix expansion bounded).
            val glyphsByClass1 = HashMap<Int, MutableList<Int>>()
            val glyphsByClass2 = HashMap<Int, MutableList<Int>>()
            for (gid in glyphToCp.keys) {
                if (gid in coveredLeft) {
                    val c1 = class1Map[gid] ?: 0
                    val list1 = glyphsByClass1.getOrPut(c1) { ArrayList() }
                    if (list1.size < 32) list1 += gid
                }
                val c2 = class2Map[gid] ?: 0
                if (c2 > 0) {
                    val list2 = glyphsByClass2.getOrPut(c2) { ArrayList() }
                    if (list2.size < 32) list2 += gid
                }
            }

            for (c1 in 0 until class1Count) {
                val leftList = glyphsByClass1[c1] ?: continue
                for (c2 in 0 until class2Count) {
                    val rightList = glyphsByClass2[c2] ?: continue
                    val recOff = subOff + 16 + c1 * class1RecBytes + c2 * class2RecBytes
                    val xAdv = readValueRecordXAdvance(buf, recOff, valueFormat1)
                    if (xAdv == 0) continue
                    for (lGid in leftList) {
                        val lCp = glyphToCp[lGid] ?: continue
                        for (rGid in rightList) {
                            val rCp = glyphToCp[rGid] ?: continue
                            if (out.size < 16_384) {
                                out += OpenTypeKerningPair(lCp, rCp, lGid, rGid, xAdv, "GPOS")
                            }
                        }
                    }
                }
            }
        }

        private fun valueRecordSize(valueFormat: Int): Int = Integer.bitCount(valueFormat and 0x00FF) * 2

        private fun readValueRecordXAdvance(buf: ByteBuffer, offset: Int, valueFormat: Int): Int {
            // Bit 0x0004 is XAdvance; bits 0x0001 (XPlacement) and 0x0002 (YPlacement) precede it.
            if ((valueFormat and 0x0004) == 0) return 0
            val precedingFields = Integer.bitCount(valueFormat and 0x0003)
            return buf.i16(offset + precedingFields * 2)
        }

        private fun parseCoverage(buf: ByteBuffer, covOff: Int, endLimit: Int): List<Int> {
            if (covOff + 4 > endLimit) return emptyList()
            return when (buf.u16(covOff)) {
                1 -> {
                    val glyphCount = buf.u16(covOff + 2)
                    val out = ArrayList<Int>(glyphCount)
                    for (i in 0 until glyphCount) {
                        val p = covOff + 4 + i * 2
                        if (p + 2 > endLimit) break
                        out += buf.u16(p)
                    }
                    out
                }
                2 -> {
                    val rangeCount = buf.u16(covOff + 2)
                    val out = ArrayList<Int>()
                    for (r in 0 until rangeCount) {
                        val p = covOff + 4 + r * 6
                        if (p + 6 > endLimit) break
                        val startG = buf.u16(p)
                        val endG = buf.u16(p + 2)
                        if (startG <= endG && endG - startG <= 65_535) {
                            for (g in startG..endG) out += g
                        }
                    }
                    out
                }
                else -> emptyList()
            }
        }

        private fun parseClassDef(buf: ByteBuffer, classOff: Int, endLimit: Int): Map<Int, Int> {
            if (classOff + 4 > endLimit) return emptyMap()
            val map = HashMap<Int, Int>()
            when (buf.u16(classOff)) {
                1 -> {
                    if (classOff + 6 > endLimit) return emptyMap()
                    val startGlyph = buf.u16(classOff + 2)
                    val glyphCount = buf.u16(classOff + 4)
                    for (i in 0 until glyphCount) {
                        val p = classOff + 6 + i * 2
                        if (p + 2 > endLimit) break
                        val cls = buf.u16(p)
                        if (cls != 0) map[startGlyph + i] = cls
                    }
                }
                2 -> {
                    val rangeCount = buf.u16(classOff + 2)
                    for (r in 0 until rangeCount) {
                        val p = classOff + 4 + r * 6
                        if (p + 6 > endLimit) break
                        val startG = buf.u16(p)
                        val endG = buf.u16(p + 2)
                        val cls = buf.u16(p + 4)
                        if (cls != 0 && startG <= endG && endG - startG <= 65_535) {
                            for (g in startG..endG) map[g] = cls
                        }
                    }
                }
            }
            return map
        }

        private fun ByteBuffer.u16(index: Int): Int = getShort(index).toInt() and 0xFFFF

        private fun ByteBuffer.i16(index: Int): Int = getShort(index).toInt()

        private fun ByteBuffer.u32(index: Int): Long = getInt(index).toLong() and 0xFFFFFFFFL

        private fun ByteBuffer.fixed1616(index: Int): Double {
            val raw = getInt(index)
            val scaled = raw.toDouble() / 65536.0
            return Math.round(scaled * 10000.0) / 10000.0
        }

        private fun ByteBuffer.ascii(index: Int, len: Int): String {
            val chars = CharArray(len)
            for (i in 0 until len) {
                val b = get(index + i).toInt() and 0xFF
                chars[i] = if (b in 0x20..0x7E) b.toChar() else '?'
            }
            return String(chars)
        }
    }
}
