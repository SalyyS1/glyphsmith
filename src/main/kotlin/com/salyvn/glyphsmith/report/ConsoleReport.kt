package com.salyvn.glyphsmith.report

import com.salyvn.glyphsmith.codepoint.Codepoints
import com.salyvn.glyphsmith.core.BuildInfo
import com.salyvn.glyphsmith.pack.BuildResult
import com.salyvn.glyphsmith.text.Snippet

/**
 * What the build says on the way past.
 *
 * The interesting numbers are the ones the author did not write down — the ascent that was derived, the
 * padding that had to be added, the advance the client will compute — so those are what the table shows.
 * Codepoints are printed as `U+` and strings as escapes, because a console that cannot render a private
 * use character shows a box and teaches the reader nothing.
 */
object ConsoleReport {
    fun lines(result: BuildResult): List<String> {
        val out = mutableListOf<String>()
        out += "== ${result.spec.font} -> ${result.root}"

        val rows = result.glyphs.map { glyph ->
            val range = glyph.codepoints.let { points ->
                if (points.size == 1) {
                    Codepoints.describe(points.first())
                } else {
                    "${Codepoints.describe(points.first())}..${Codepoints.describe(points.last())}"
                }
            }
            listOf(
                glyph.spec.id,
                range,
                "${glyph.solved.texture.width}x${glyph.solved.texture.height}",
                "h ${glyph.solved.height}",
                "a ${glyph.solved.ascent}",
                "adv ${glyph.solved.totalAdvance}",
            )
        }
        val widths = (0 until 6).map { column -> rows.maxOfOrNull { row -> row[column].length } ?: 0 }
        result.glyphs.forEachIndexed { index, glyph ->
            val row = rows[index].mapIndexed { column, cell -> cell.padEnd(widths[column]) }.joinToString("  ")
            out += "   $row"
            glyph.notes.forEach { note -> out += "        $note" }
        }

        result.spec.takeIf { it.usesShifts }?.let { spec ->
            out += "   shift glyphs cover 1..${spec.shiftRange}px in both directions"
        }

        out += ""
        result.glyphs.forEach { glyph -> out += "   ${glyph.spec.id}: ${glyph.snippet.escaped}" }

        if (result.warnings.isNotEmpty()) {
            out += ""
            result.warnings.forEach { warning -> out += "   warning: $warning" }
        }
        return out
    }

    /** The file that holds the strings themselves, in every form something downstream might need. */
    fun snippetFile(result: BuildResult): String {
        val out = StringBuilder()
        out.append("# glyphsmith ${BuildInfo.version} — strings for ${result.spec.font}\n")
        out.append("# Rebuild with: glyphsmith build ${result.spec.source.fileName}\n")
        out.append("#\n")
        out.append("# Each glyph is listed four ways. Use 'raw' in a YAML config, 'escape' where an editor or a\n")
        out.append("# database would mangle a high codepoint, 'minimessage' in anything Adventure parses, and\n")
        out.append("# 'json' in a raw text component. A placed glyph already carries its own shifts.\n")
        result.glyphs.forEach { glyph ->
            out.append("\n## ${glyph.spec.id}\n")
            out.append("codepoints   ${glyph.codepoints.joinToString(" ") { code -> Codepoints.describe(code) }}\n")
            out.append("height       ${glyph.solved.height}\n")
            out.append("ascent       ${glyph.solved.ascent}\n")
            out.append("advance      ${glyph.solved.advances.joinToString(" + ")} = ${glyph.solved.totalAdvance}px\n")
            out.append(entry(glyph.snippet))
        }
        return out.toString()
    }

    private fun entry(snippet: Snippet): String = buildString {
        append("minimessage  ${snippet.miniMessage}\n")
        append("escape       ${snippet.escaped}\n")
        append("json         ${snippet.component}\n")
        append("raw          ${snippet.raw}\n")
    }
}
