package com.salyvn.glyphsmith.text

import com.salyvn.glyphsmith.pack.Json

/**
 * The same characters written the four ways a server owner has to paste them: as themselves for a YAML
 * config, as escapes for anything that mangles high codepoints, as MiniMessage, and as a JSON text
 * component.
 */
data class Snippet(val raw: String, val font: String) {
    /** `\uXXXX` per UTF-16 unit, which is a surrogate pair above the BMP — what Java and JSON want. */
    val escaped: String get() = raw.map { char -> "\\u%04X".format(char.code) }.joinToString("")

    /** `<font:namespace:id>` is Adventure's font tag; the namespace stays explicit on purpose. */
    val miniMessage: String get() = "<font:$font>$raw</font>"

    val component: String get() = Json.Obj(
        "text" to Json.Text(raw),
        "font" to Json.Text(font),
    ).compact()

    fun asFormat(format: SnippetFormat): String = when (format) {
        SnippetFormat.RAW -> raw
        SnippetFormat.ESCAPE -> escaped
        SnippetFormat.MINIMESSAGE -> miniMessage
        SnippetFormat.JSON -> component
    }
}

enum class SnippetFormat(val id: String) {
    RAW("raw"),
    ESCAPE("escape"),
    MINIMESSAGE("minimessage"),
    JSON("json"),
    ;

    companion object {
        fun of(id: String): SnippetFormat = entries.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Unknown format '$id'. Known: ${entries.joinToString(", ") { it.id }}.")
    }
}
