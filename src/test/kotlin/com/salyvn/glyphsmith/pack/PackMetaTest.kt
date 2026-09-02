package com.salyvn.glyphsmith.pack

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The `pack.mcmeta` rules, which are four fields that validate against each other and change meaning at
 * format 65.
 */
class PackMetaTest {

    @Test
    fun `a modern only pack states the range and nothing else`() {
        val json = PackMeta.render("test", PackFormat(88), PackFormat(97, 1))

        assertTrue(json.contains("\"min_format\": 88"))
        assertTrue(json.contains("\"max_format\": [\n"), "a minor version is written as a two element list")
        assertFalse(json.contains("supported_formats"), "the client refuses that key above format 64")
        assertFalse(json.contains("pack_format"), "and does not need this one either")
    }

    @Test
    fun `a pack that reaches back before format 65 carries the legacy fields as well`() {
        val json = PackMeta.render("test", PackFormat(15), PackFormat(88))

        assertTrue(json.contains("\"pack_format\": 15"), "old clients read only this")
        // supported_formats has to start on the same major as min_format and stop at 64, the last format
        // that a client without minor versions understands.
        assertTrue(json.contains("\"supported_formats\": [\n      15,\n      64\n    ]"), json)
        assertTrue(json.contains("\"min_format\": 15"))
        assertTrue(json.contains("\"max_format\": 88"))
    }

    @Test
    fun `a range that stays in the legacy era stops supported_formats at its own ceiling`() {
        val json = PackMeta.render("test", PackFormat(15), PackFormat(46))

        assertTrue(json.contains("\"supported_formats\": [\n      15,\n      46\n    ]"), json)
    }

    @Test
    fun `a multi version pack cannot start below fifteen`() {
        val failure = assertThrows<PackMetaException> { PackMeta.render("test", PackFormat(9), PackFormat(88)) }

        assertTrue(failure.message!!.contains("15"))
    }

    @Test
    fun `an inverted range is refused`() {
        assertThrows<PackMetaException> { PackMeta.render("test", PackFormat(88), PackFormat(46)) }
    }

    @Test
    fun `the description is quoted rather than pasted`() {
        val json = PackMeta.render("a \"quoted\" pack", PackFormat(88), PackFormat(88))

        assertTrue(json.contains("\\\"quoted\\\""), json)
    }

    @Test
    fun `the whole file is one pack section`() {
        val json = PackMeta.render("test", PackFormat(88), PackFormat(88))

        assertEquals(0, json.indexOf("{"))
        assertTrue(json.trimEnd().endsWith("}"))
        assertTrue(json.contains("\"pack\": {"))
    }
}
