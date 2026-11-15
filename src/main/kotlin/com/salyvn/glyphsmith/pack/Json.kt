package com.salyvn.glyphsmith.pack

import java.math.BigDecimal

/**
 * A very small JSON writer.
 *
 * The files this tool emits are read by the game and then by whoever has to debug them, so the output is
 * fixed rather than incidental: keys keep the order they were declared in, indentation is two spaces, and
 * every character above plain ASCII becomes a `\uXXXX` escape. That last part matters - a glyph's
 * codepoint lives in a `chars` row, and a file full of raw private-use characters survives neither an
 * editor that guesses encodings nor a diff.
 */
sealed class Json {
    data class Text(val value: String) : Json()

    data class Num(val value: Int) : Json()

    data class Dec(val value: Double) : Json() {
        init {
            require(value.isFinite()) { "JSON floating-point number must be finite, got $value" }
        }
    }

    data class Bool(val value: Boolean) : Json()

    data class Arr(val items: List<Json>) : Json()

    data class Obj(val entries: List<Pair<String, Json>>) : Json() {
        constructor(vararg entries: Pair<String, Json>) : this(entries.toList())
    }

    fun render(indent: String = ""): String {
        val inner = "$indent  "
        return when (this) {
            is Text -> quote(value)
            is Num -> value.toString()
            is Dec -> formatDecimal(value)
            is Bool -> value.toString()
            is Arr -> if (items.isEmpty()) {
                "[]"
            } else {
                items.joinToString(",\n", "[\n", "\n$indent]") { item -> inner + item.render(inner) }
            }
            is Obj -> if (entries.isEmpty()) {
                "{}"
            } else {
                entries.joinToString(",\n", "{\n", "\n$indent}") { (key, value) ->
                    "$inner${quote(key)}: ${value.render(inner)}"
                }
            }
        }
    }

    /** One line, for something a person is going to paste into a config or a command. */
    fun compact(): String = when (this) {
        is Text -> quote(value)
        is Num -> value.toString()
        is Dec -> formatDecimal(value)
        is Bool -> value.toString()
        is Arr -> items.joinToString(",", "[", "]") { item -> item.compact() }
        is Obj -> entries.joinToString(",", "{", "}") { (key, value) -> "${quote(key)}:${value.compact()}" }
    }

    companion object {
        private fun formatDecimal(value: Double): String {
            val stripped = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
            return if ('.' in stripped) stripped else "$stripped.0"
        }

        fun quote(value: String): String {
            val out = StringBuilder(value.length + 2)
            out.append('"')
            for (char in value) {
                when {
                    char == '"' -> out.append("\\\"")
                    char == '\\' -> out.append("\\\\")
                    char == '\n' -> out.append("\\n")
                    char == '\r' -> out.append("\\r")
                    char == '\t' -> out.append("\\t")
                    char.code in 0x20..0x7E -> out.append(char)
                    else -> out.append("\\u%04X".format(char.code))
                }
            }
            out.append('"')
            return out.toString()
        }
    }
}
