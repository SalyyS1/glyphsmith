package com.salyvn.glyphsmith.codepoint

import com.salyvn.glyphsmith.audit.FindingSeverity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UnicodeCoverageValidatorTest {

    @Test
    fun `detects lone high and lone low UTF-16 surrogates`() {
        // Valid surrogate pair: U+1F600 (Grinning Face)
        val validPair = "\uD83D\uDE00"
        val cleanIssues = UnicodeCoverageValidator.validateSurrogates(validPair)
        assertTrue(cleanIssues.isEmpty())

        // Lone high surrogate
        val loneHigh = "Hello \uD83D World"
        val highIssues = UnicodeCoverageValidator.validateSurrogates(loneHigh)
        assertEquals(1, highIssues.size)
        assertTrue(highIssues.first().contains("Lone high surrogate"))

        // Lone low surrogate
        val loneLow = "Hello \uDE00 World"
        val lowIssues = UnicodeCoverageValidator.validateSurrogates(loneLow)
        assertEquals(1, lowIssues.size)
        assertTrue(lowIssues.first().contains("Lone low surrogate"))

        // Audit finding generation
        val findings = UnicodeCoverageValidator.checkSurrogateHazards(
            text = loneHigh,
            fontId = "minecraft:default",
            providerIndex = 0,
            packLabel = "test",
            filePath = "font.json",
        )
        assertEquals(1, findings.size)
        assertEquals("UNICODE_SURROGATE_HAZARD", findings.first().rule)
        assertEquals(FindingSeverity.ERROR, findings.first().severity)
    }

    @Test
    fun `flags non-BMP characters in legacy bitmap providers`() {
        val nonBmpCodepoints = listOf(0x1F600, 0x1F601, 0x41) // 2 SMP emoticons + 1 ASCII

        val findings = UnicodeCoverageValidator.checkPlaneSupport(
            providerType = "bitmap",
            codepoints = nonBmpCodepoints,
            fontId = "minecraft:default",
            providerIndex = 0,
            format = 15, // Legacy format
            packLabel = "pack",
            filePath = "default.json",
        )

        assertEquals(1, findings.size)
        assertEquals("UNICODE_PLANE_UNSUPPORTED_PROVIDER", findings.first().rule)
        assertEquals(FindingSeverity.WARNING, findings.first().severity)
        assertTrue(findings.first().message.contains("Plane 1"))

        // In format 97, modern surrogate grid parsing is supported
        val cleanFindings = UnicodeCoverageValidator.checkPlaneSupport(
            providerType = "bitmap",
            codepoints = nonBmpCodepoints,
            fontId = "minecraft:default",
            providerIndex = 0,
            format = 97,
            packLabel = "pack",
            filePath = "default.json",
        )
        assertTrue(cleanFindings.isEmpty())
    }

    @Test
    fun `analyzes Unicode 16 plane and block coverage`() {
        val codepoints = mutableListOf<Int>()
        // Full ASCII
        for (cp in 0x0020..0x007E) codepoints += cp
        // Some Cyrillic
        for (cp in 0x0410..0x042F) codepoints += cp
        // Some Plane 15 PUA
        for (cp in 0xF0000..0xF0010) codepoints += cp

        val report = UnicodeCoverageValidator.analyzeCoverage(codepoints)

        assertEquals(codepoints.size, report.totalMappedCodepoints)
        assertEquals(2, report.planeCounts.size) // Plane 0, Plane 15
        assertTrue(report.planeCounts.containsKey(0))
        assertTrue(report.planeCounts.containsKey(15))

        val asciiBlock = report.blockCoverages.first { it.name.contains("ASCII") }
        assertEquals(100.0, asciiBlock.coveragePercent)
        assertTrue(asciiBlock.sampleMissing.isEmpty())

        val cyrillicBlock = report.blockCoverages.first { it.name.contains("Cyrillic") }
        assertTrue(cyrillicBlock.coveragePercent > 0.0)
        assertTrue(cyrillicBlock.coveragePercent < 100.0)
        assertTrue(cyrillicBlock.sampleMissing.isNotEmpty())
    }

    @Test
    fun `detects essential script coverage gaps`() {
        // Only 10 ASCII characters mapped (severe gap)
        val scarceAscii = (0x0020..0x002A).toSet()
        val gap = UnicodeCoverageValidator.checkScriptCoverage(
            scriptName = "ASCII",
            codepoints = scarceAscii,
            fontId = "minecraft:default",
            providerIndex = 0,
            packLabel = "pack",
            filePath = "default.json",
        )

        assertNotNull(gap)
        assertEquals("UNICODE_COVERAGE_GAP", gap?.rule)
        assertEquals(FindingSeverity.WARNING, gap?.severity)
        assertTrue(gap?.message?.contains("incomplete coverage") == true)
    }
}
