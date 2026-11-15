package com.salyvn.glyphsmith.codepoint

import com.salyvn.glyphsmith.audit.AuditFinding
import com.salyvn.glyphsmith.audit.FindingSeverity
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.round

@Serializable
data class BlockCoverage(
    val name: String,
    val plane: Int,
    val rangeStart: String,
    val rangeEnd: String,
    val totalBlockChars: Int,
    val mappedCount: Int,
    val coveragePercent: Double,
    val sampleMissing: List<String>,
)

@Serializable
data class UnicodeCoverageReport(
    val totalMappedCodepoints: Int,
    val planeCounts: Map<Int, Int>,
    val blockCoverages: List<BlockCoverage>,
    val surrogateHazards: List<String>,
    val warnings: List<String>,
) {
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
 * Unicode 16.0 plane coverage validator and UTF-16 surrogate safety auditor.
 *
 * Validates glyph codepoints across all 17 Unicode planes (BMP, SMP, SIP, TIP, SSP, and Plane 15/16 PUAs).
 * Audits lone/unpaired surrogate hazards (U+D800..U+DFFF) which crash Java UTF-8 encoders and corrupt
 * Minecraft chat buffers, and flags bitmap providers attempting Plane 1+ allocations without 32-bit surrogate support.
 */
object UnicodeCoverageValidator {

    data class UnicodeBlockDef(
        val name: String,
        val plane: Int,
        val range: IntRange,
        val isEssential: Boolean = false,
    )

    val STANDARD_BLOCKS = listOf(
        UnicodeBlockDef("Basic Latin (ASCII)", 0, 0x0020..0x007E, isEssential = true),
        UnicodeBlockDef("Latin-1 Supplement", 0, 0x00A0..0x00FF, isEssential = true),
        UnicodeBlockDef("Latin Extended-A", 0, 0x0100..0x017F),
        UnicodeBlockDef("Latin Extended-B", 0, 0x0180..0x024F),
        UnicodeBlockDef("Greek and Coptic", 0, 0x0370..0x03FF),
        UnicodeBlockDef("Cyrillic", 0, 0x0400..0x04FF),
        UnicodeBlockDef("General Punctuation", 0, 0x2000..0x206F),
        UnicodeBlockDef("Currency Symbols", 0, 0x20A0..0x20CF),
        UnicodeBlockDef("Number Forms", 0, 0x2150..0x218F),
        UnicodeBlockDef("Arrows", 0, 0x2190..0x21FF),
        UnicodeBlockDef("Mathematical Operators", 0, 0x2200..0x22FF),
        UnicodeBlockDef("Miscellaneous Symbols", 0, 0x2600..0x26FF),
        UnicodeBlockDef("CJK Unified Ideographs", 0, 0x4E00..0x9FFF),
        UnicodeBlockDef("BMP Private Use Area", 0, 0xE000..0xF8FF),
        UnicodeBlockDef("SMP Emoticons and Symbols", 1, 0x1F300..0x1F64F),
        UnicodeBlockDef("Supplementary PUA-A (Plane 15)", 15, 0xF0000..0xFFFFD),
        UnicodeBlockDef("Supplementary PUA-B (Plane 16)", 16, 0x100000..0x10FFFD),
    )

    fun getPlane(codepoint: Int): Int = codepoint shr 16

    fun validateSurrogates(text: String): List<String> {
        val issues = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 < text.length && Character.isLowSurrogate(text[i + 1])) {
                    i += 2
                    continue
                } else {
                    issues += "Lone high surrogate U+${ch.code.toString(16).uppercase().padStart(4, '0')} at character index $i."
                }
            } else if (Character.isLowSurrogate(ch)) {
                issues += "Lone low surrogate U+${ch.code.toString(16).uppercase().padStart(4, '0')} at character index $i."
            }
            i++
        }
        return issues
    }

    fun checkSurrogateHazards(
        text: String,
        fontId: String,
        providerIndex: Int?,
        packLabel: String?,
        filePath: String?,
    ): List<AuditFinding> {
        val issues = validateSurrogates(text)
        return issues.map { issue ->
            AuditFinding(
                rule = "UNICODE_SURROGATE_HAZARD",
                severity = FindingSeverity.ERROR,
                fontId = fontId,
                providerIndex = providerIndex,
                message = "Malformed UTF-16 surrogate detected: $issue Unpaired surrogates crash Java String encoders and corrupt chat rendering.",
                pack = packLabel,
                filePath = filePath,
            )
        }
    }

    fun checkPlaneSupport(
        providerType: String,
        codepoints: Collection<Int>,
        fontId: String,
        providerIndex: Int,
        format: Int?,
        packLabel: String?,
        filePath: String?,
    ): List<AuditFinding> {
        val findings = mutableListOf<AuditFinding>()
        if (providerType == "bitmap") {
            // Plane 1..14 (0x10000..0xEFFFF): SMP/SIP/TIP characters, excluding standard Plane 15/16 Supplementary PUAs
            val nonPuaSupplementary = codepoints.filter { it in 0x10000..0xEFFFF }
            if (nonPuaSupplementary.isNotEmpty() && format != null && format < 97) {
                val sample = nonPuaSupplementary.first()
                val plane = getPlane(sample)
                findings += AuditFinding(
                    rule = "UNICODE_PLANE_UNSUPPORTED_PROVIDER",
                    severity = FindingSeverity.WARNING,
                    fontId = fontId,
                    providerIndex = providerIndex,
                    message = "Bitmap provider #$providerIndex maps ${nonPuaSupplementary.size} non-BMP character(s) in Plane $plane (e.g. U+${sample.toString(16).uppercase()}); legacy bitmap grid parsers prior to format 97 require TTF or dual-era format 97 overlays.",
                    pack = packLabel,
                    filePath = filePath,
                )
            }
        }
        return findings
    }

    fun checkScriptCoverage(
        scriptName: String,
        codepoints: Set<Int>,
        fontId: String,
        providerIndex: Int?,
        packLabel: String?,
        filePath: String?,
    ): AuditFinding? {
        val block = STANDARD_BLOCKS.find { it.name.contains(scriptName, ignoreCase = true) } ?: return null
        val total = block.range.count()
        val mapped = block.range.count { it in codepoints }
        val pct = if (total > 0) (mapped.toDouble() / total.toDouble()) * 100.0 else 0.0

        if (block.isEssential && pct < 90.0) {
            val missing = block.range.filter { it !in codepoints }.take(3).map { "U+${it.toString(16).uppercase()}" }
            return AuditFinding(
                rule = "UNICODE_COVERAGE_GAP",
                severity = FindingSeverity.WARNING,
                fontId = fontId,
                providerIndex = providerIndex,
                message = "Essential script '${block.name}' has incomplete coverage ($mapped/$total, ${round(pct * 10.0) / 10.0}%); missing glyphs include ${missing.joinToString(", ")}.",
                pack = packLabel,
                filePath = filePath,
            )
        }
        return null
    }

    fun analyzeCoverage(codepoints: Collection<Int>): UnicodeCoverageReport {
        val uniqueCodepoints = codepoints.toSet()
        val planeCounts = uniqueCodepoints.groupBy { getPlane(it) }.mapValues { it.value.size }

        val blockCoverages = STANDARD_BLOCKS.map { block ->
            val totalInBlock = block.range.count()
            val mappedInBlock = block.range.count { it in uniqueCodepoints }
            val pct = if (totalInBlock > 0) round((mappedInBlock.toDouble() / totalInBlock.toDouble()) * 1000.0) / 10.0 else 0.0
            val missingSamples = block.range
                .filter { it !in uniqueCodepoints }
                .take(4)
                .map { "U+${it.toString(16).uppercase()}" }

            BlockCoverage(
                name = block.name,
                plane = block.plane,
                rangeStart = "U+${block.range.first.toString(16).uppercase().padStart(4, '0')}",
                rangeEnd = "U+${block.range.last.toString(16).uppercase().padStart(4, '0')}",
                totalBlockChars = totalInBlock,
                mappedCount = mappedInBlock,
                coveragePercent = pct,
                sampleMissing = missingSamples,
            )
        }

        return UnicodeCoverageReport(
            totalMappedCodepoints = uniqueCodepoints.size,
            planeCounts = planeCounts,
            blockCoverages = blockCoverages,
            surrogateHazards = emptyList(),
            warnings = emptyList(),
        )
    }
}
