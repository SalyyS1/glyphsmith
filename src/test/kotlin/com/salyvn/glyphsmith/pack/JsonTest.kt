package com.salyvn.glyphsmith.pack

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class JsonTest {

    @Test
    fun `keys keep the order they were written in`() {
        val json = Json.Obj(
            "type" to Json.Text("bitmap"),
            "height" to Json.Num(9),
            "ascent" to Json.Num(8),
        ).compact()

        assertEquals("""{"type":"bitmap","height":9,"ascent":8}""", json)
    }

    @Test
    fun `anything above plain ascii becomes an escape`() {
        // A private use codepoint above the BMP is a surrogate pair, and both halves are escaped so the
        // file stays readable in any editor and survives a copy-paste.
        assertEquals("\"\\uDB80\\uDC00\"", Json.quote(String(Character.toChars(0xF0000))))
        assertEquals("\"a\\\"b\"", Json.quote("a\"b"))
        assertEquals("\"a\\\\b\"", Json.quote("a\\b"))
        assertEquals("\"a\\nb\"", Json.quote("a\nb"))
    }

    @Test
    fun `nesting is indented two spaces at a time`() {
        val json = Json.Obj("pack" to Json.Obj("format" to Json.Num(88))).render()

        assertEquals("{\n  \"pack\": {\n    \"format\": 88\n  }\n}", json)
    }

    @Test
    fun `an empty container is written on one line either way`() {
        assertEquals("[]", Json.Arr(emptyList()).render())
        assertEquals("{}", Json.Obj(emptyList()).render())
    }
}
